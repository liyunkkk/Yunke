"""Runtime-owned bridge to DeepSeek Harness (dsh) over ACP stdio.

上层（DshSubagentTool）只做两件事：把参数拼成一行 JSON 传给本脚本，读回一行 JSON。
所有 ACP 细节都封在这里，Kotlin 侧不必维护双向管道。

契约全部来自实机取证（dsh 0.1.5-rc.1 / `--profile acp`）：
  - initialize 的 protocolVersion 必须是数字；传字符串会被 -32602 拒绝；
  - agentCapabilities.sessionCapabilities = {close, list, resume}，
    promptCapabilities = {image:false, audio:false, embeddedContext:false}（本构建无附件能力）；
  - 续接会话用 session/resume（session/load 是 Method not found）；
  - session/prompt 是请求-响应式，stopReason 在响应里；用通知发出会永远等不到完成；
  - 进度来自 session/update：agent_message_chunk / agent_thought_chunk / tool_call /
    tool_call_update / usage_update；
  - 权限询问走 session/requestPermission，实测常规任务不触发，但必须能应答，否则静默卡死。
"""
import json
import os
import re
import signal
import subprocess
import sys
import threading
import time

PROTOCOL_VERSION = 1
DEFAULT_TIMEOUT = 300.0
SHUTDOWN_GRACE = 3.0
STDOUT_LIMIT = 15000  # 终端桥在 16000 UTF-16 单位处截断，这里留余量
RESPONSE_LIMIT = 8000  # 回传正文上限，超出部分丢弃并标记
STDERR_TAIL = 8


class Refused(ValueError):
    """稳定的错误码 + 少量非敏感细节，供上层分类处理。"""

    def __init__(self, code, **details):
        super().__init__(code)
        self.code = code
        self.details = details


def units(text):
    """UTF-16 单位数：与终端桥的截断口径一致，避免回传被截成非法 JSON。"""
    return len(text.encode('utf-16-le')) // 2


def log(message):
    """诊断只走 stderr，stdout 必须只留一行 JSON。"""
    print(message, file=sys.stderr, flush=True)


def resolve_runtime():
    explicit = (os.environ.get('DSH_RUNTIME_BIN') or '').strip()
    if explicit and os.path.isfile(explicit):
        return explicit
    try:
        from deepseek_harness_runtime import resolve_bundled_launch_args
        args = resolve_bundled_launch_args()
        if args:
            return str(args[0])
    except Exception as error:  # 仅诊断
        log('resolve_bundled_launch_args failed: %s' % type(error).__name__)
    return None


def resolve_credentials(args):
    """返回 (base_url, api_key)。优先 0600 密钥文件，避免密钥出现在进程环境里。"""
    base = (os.environ.get('DEEPSEEK_BASE_URL') or '').strip() or None
    key = (os.environ.get('DEEPSEEK_API_KEY') or '').strip() or None
    path = args.get('key_file') or os.environ.get('DSH_KEY_FILE')
    if path:
        try:
            with open(path, encoding='utf-8') as handle:
                for line in handle:
                    line = line.strip()
                    if not line or line.startswith('#') or '=' not in line:
                        continue
                    name, _, value = line.partition('=')
                    name = name.strip().upper()
                    value = value.strip().strip('"').strip("'")
                    if name in ('DEEPSEEK_BASE_URL', 'BASE_URL') and value:
                        base = value
                    elif name in ('DEEPSEEK_API_KEY', 'API_KEY') and value:
                        key = value
        except FileNotFoundError:
            log('credential file missing: %s' % path)
        except Exception as error:
            log('credential file unreadable: %s' % type(error).__name__)
    if args.get('base_url'):
        base = args['base_url']
    return base, key


class AcpClient:
    """单进程、单会话的极简 ACP 客户端。"""

    def __init__(self, runtime, cwd, home, base_url, api_key):
        env = dict(os.environ)
        env['DSH_HOME'] = home
        env['DEEPSEEK_BASE_URL'] = base_url
        env['DEEPSEEK_API_KEY'] = api_key
        env['NO_COLOR'] = '1'
        env['TERM'] = 'dumb'
        self.proc = subprocess.Popen(
            [runtime, '--profile', 'acp'], cwd=cwd, env=env,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, bufsize=1, start_new_session=True,  # 独立进程组，超时可整组回收
        )
        self._lock = threading.Lock()
        self._messages = []
        self._stderr = []
        self._next_id = 0
        self._closed = False
        threading.Thread(target=self._read_stdout, daemon=True).start()
        threading.Thread(target=self._read_stderr, daemon=True).start()

    def _read_stdout(self):
        for line in self.proc.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                continue  # 日志噪音
            with self._lock:
                self._messages.append(message)
            self._answer_permission(message)

    def _read_stderr(self):
        for line in self.proc.stderr:
            line = line.rstrip()
            if not line:
                continue
            self._stderr.append(line)
            if len(self._stderr) > 200:
                del self._stderr[0]

    def _answer_permission(self, message):
        """委派场景没有交互式用户：选第一个「允许」项，认不出就拒绝，不误放行。"""
        if message.get('method') != 'session/requestPermission' or 'id' not in message:
            return
        options = (message.get('params') or {}).get('options') or []
        allow = None
        for option in options:
            if 'allow' in json.dumps(option, ensure_ascii=False).lower():
                allow = option
                break
        result = ({'outcome': {'outcome': 'selected', 'optionId': allow.get('optionId')}}
                  if allow else {'outcome': {'outcome': 'cancelled'}})
        try:
            self._write({'jsonrpc': '2.0', 'id': message['id'], 'result': result})
        except Exception:
            pass

    def _write(self, payload):
        if self.proc.stdin is None:
            raise BrokenPipeError('stdin closed')
        self.proc.stdin.write(json.dumps(payload, ensure_ascii=False) + '\n')
        self.proc.stdin.flush()

    def call(self, method, params, timeout):
        self._next_id += 1
        request_id = self._next_id
        self._write({'jsonrpc': '2.0', 'id': request_id, 'method': method, 'params': params})
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self._lock:
                for message in self._messages:
                    if message.get('id') == request_id:
                        return message
            if self.proc.poll() is not None:
                time.sleep(0.2)
                with self._lock:
                    for message in self._messages:
                        if message.get('id') == request_id:
                            return message
                raise Refused('RUNTIME_EXITED', method=method, exit_code=self.proc.returncode)
            time.sleep(0.1)
        raise TimeoutError('%s timeout (%.0fs)' % (method, timeout))

    def mark(self):
        with self._lock:
            return len(self._messages)

    def collect(self, since):
        """返回 (助手正文, 工具调用, 用量)。"""
        texts, tools, usage = [], [], None
        with self._lock:
            snapshot = list(self._messages[since:])
        for message in snapshot:
            if message.get('method') != 'session/update':
                continue
            update = (message.get('params') or {}).get('update')
            if not isinstance(update, dict):
                continue
            kind = update.get('sessionUpdate')
            if kind == 'agent_message_chunk':
                content = update.get('content')
                if isinstance(content, dict) and isinstance(content.get('text'), str):
                    texts.append(content['text'])
            elif kind == 'tool_call':
                tools.append({'title': update.get('title'), 'status': update.get('status')})
            elif kind == 'tool_call_update':
                status, title = update.get('status'), update.get('title')
                if title is None and tools:
                    tools[-1]['status'] = status or tools[-1].get('status')
                elif title is not None:
                    tools.append({'title': title, 'status': status})
            elif kind == 'usage_update':
                usage = {'used': update.get('used'), 'size': update.get('size')}
        return ''.join(texts).strip(), tools, usage

    def stderr_tail(self, limit=600):
        return '\n'.join(self._stderr[-STDERR_TAIL:])[:limit]

    def close(self):
        if self._closed:
            return
        self._closed = True
        try:
            if self.proc.stdin:
                self.proc.stdin.close()  # ACP 以 stdin 关闭作为断开信号
        except Exception:
            pass
        try:
            self.proc.wait(timeout=SHUTDOWN_GRACE)
        except Exception:
            self.reap()

    def reap(self):
        """整组回收，绝不留孤儿进程。"""
        if self.proc.poll() is not None:
            return
        for sig in (signal.SIGTERM, signal.SIGKILL):
            try:
                os.killpg(os.getpgid(self.proc.pid), sig)
            except Exception:
                pass
            try:
                self.proc.wait(timeout=SHUTDOWN_GRACE)
                return
            except Exception:
                continue


def open_client(args, runtime, base, key):
    cwd = args.get('cwd') or '/workspace'
    if not os.path.isdir(cwd):
        raise Refused('CWD_MISSING', cwd=cwd)
    home = args.get('home') or os.environ.get('DSH_HOME') or '/root/.dsh'
    os.makedirs(home, exist_ok=True)
    client = AcpClient(runtime, cwd, home, base, key)
    try:
        init = client.call('initialize', {'protocolVersion': PROTOCOL_VERSION,
                                          'clientCapabilities': {}}, 60)
    except Exception:
        client.reap()
        raise
    if 'error' in init:
        client.reap()
        raise Refused('INIT_FAILED', detail=json.dumps(init['error'], ensure_ascii=False)[:300])
    return client


def action_check(args):
    runtime = resolve_runtime()
    base, key = resolve_credentials(args)
    if not runtime:
        raise Refused('RUNTIME_MISSING', hint='install deepseek-harness-runtime-bin')
    if not key:
        raise Refused('ENDPOINT_MISSING', hint='provide key_file or DEEPSEEK_API_KEY')
    return {'runtime_present': os.path.isfile(runtime), 'endpoint': base, 'has_credentials': True}


def action_models(args):
    runtime = resolve_runtime()
    base, key = resolve_credentials(args)
    if not runtime:
        raise Refused('RUNTIME_MISSING')
    if not key:
        raise Refused('ENDPOINT_MISSING')
    client = open_client(args, runtime, base, key)
    try:
        created = client.call('session/new', {'cwd': args.get('cwd') or '/workspace',
                                              'mcpServers': []}, 60)
        if 'error' in created:
            raise Refused('SESSION_FAILED', detail=json.dumps(created['error'], ensure_ascii=False)[:300])
        models, efforts = [], []
        for option in (created.get('result') or {}).get('configOptions', []):
            if option.get('id') == 'model':
                for group in option.get('options', []):
                    for item in group.get('options', []) if 'options' in group else [group]:
                        models.append({'name': item.get('name'), 'value': item.get('value')})
            elif option.get('id') == 'reasoning_effort':
                efforts = [{'value': o.get('value'), 'name': o.get('name')}
                           for o in option.get('options', [])]
        return {'models': models, 'reasoning_efforts': efforts}
    finally:
        client.close()
        client.reap()


def action_run(args):
    task = (args.get('task') or '').strip()
    if not task:
        raise Refused('BAD_INPUT', hint='task is required')
    timeout = float(args.get('timeout') or DEFAULT_TIMEOUT)
    runtime = resolve_runtime()
    base, key = resolve_credentials(args)
    if not runtime:
        raise Refused('RUNTIME_MISSING', hint='install deepseek-harness-runtime-bin')
    if not key:
        raise Refused('ENDPOINT_MISSING', hint='provide key_file or DEEPSEEK_API_KEY')
    started = time.time()
    client = open_client(args, runtime, base, key)
    try:
        session_id = (args.get('session') or '').strip() or None
        resumed = False
        if session_id:
            resumed_call = client.call('session/resume', {'sessionId': session_id,
                                                          'cwd': args.get('cwd') or '/workspace',
                                                          'mcpServers': []}, 60)
            if 'error' in resumed_call:
                # 会话不存在（DSH_HOME 被清过或 id 过期）：退回新建，不算失败
                log('session/resume failed, creating new: %s'
                    % json.dumps(resumed_call['error'], ensure_ascii=False)[:200])
                session_id = None
            else:
                resumed = True
        if not session_id:
            created = client.call('session/new', {'cwd': args.get('cwd') or '/workspace',
                                                  'mcpServers': []}, 60)
            if 'error' in created:
                raise Refused('SESSION_FAILED',
                              detail=json.dumps(created['error'], ensure_ascii=False)[:300])
            session_id = (created.get('result') or {}).get('sessionId')
        if not session_id:
            raise Refused('SESSION_FAILED', detail='runtime returned no sessionId')

        for config_id, value in (('model', args.get('model_value')),
                                 ('reasoning_effort', args.get('effort'))):
            if not value:
                continue
            try:
                client.call('session/setConfigOption',
                            {'sessionId': session_id, 'configId': config_id, 'value': value}, 30)
            except Exception as error:
                log('set %s failed (ignored): %s' % (config_id, type(error).__name__))

        mark = client.mark()
        remaining = max(5.0, timeout - (time.time() - started))
        try:
            response = client.call('session/prompt', {
                'sessionId': session_id,
                'prompt': [{'type': 'text', 'text': task}],
            }, remaining)
            if 'error' in response:
                raise Refused('PROMPT_FAILED',
                              detail=json.dumps(response['error'], ensure_ascii=False)[:300])
            stop_reason = (response.get('result') or {}).get('stopReason')
        except TimeoutError:
            try:
                client._write({'jsonrpc': '2.0', 'method': 'session/cancel',
                               'params': {'sessionId': session_id}})
                time.sleep(1.0)
            except Exception:
                pass
            text, tools, _ = client.collect(mark)
            raise Refused('PROMPT_TIMEOUT', session_id=session_id, resumed=resumed,
                          partial_response=text[-2000:], tool_calls=tools,
                          elapsed_seconds=round(time.time() - started, 2),
                          stderr=client.stderr_tail())

        text, tools, usage = client.collect(mark)
        truncated = len(text) > RESPONSE_LIMIT
        return {
            'session_id': session_id,
            'resumed': resumed,
            'response': text[:RESPONSE_LIMIT],
            'response_truncated': truncated,
            'stop_reason': stop_reason,
            'tool_calls': tools,
            'usage': usage,
            'elapsed_seconds': round(time.time() - started, 2),
        }
    finally:
        client.close()
        client.reap()


ACTIONS = {'check': action_check, 'models': action_models, 'run': action_run}


def execute(args):
    action = args.get('action') or 'run'
    handler = ACTIONS.get(action)
    if handler is None:
        raise Refused('UNKNOWN_ACTION', action=action)
    return handler(args)


def response_line(result):
    """一行 JSON，且绝不被截断成无法解析的片段。"""
    line = json.dumps({'ok': True, **result}, ensure_ascii=False)
    if units(line) > STDOUT_LIMIT:
        line = json.dumps({'ok': False, 'code': 'OUTPUT_TOO_LARGE',
                           'hint': 'narrow the task or lower timeout'})
    return line


if __name__ == '__main__':
    try:
        print(response_line(execute(json.loads(sys.argv[1]))))
    except Refused as error:
        print(json.dumps({'ok': False, 'code': error.code, **error.details}, ensure_ascii=False))
    except Exception as error:
        print(json.dumps({'ok': False, 'code': 'DSH_BRIDGE_FAILED',
                          'detail': type(error).__name__}, ensure_ascii=False))
