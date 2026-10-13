"""Provisioning script for DeepSeek Harness (dsh) runtime in Linux container.

Follows the workspace/bridge single-line JSON IPC protocol:
  - Takes a JSON line via sys.argv[1];
  - Prints exactly one JSON line to stdout;
  - Diagnostics go to stderr;
  - Bounded UTF-16 output length.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import uuid
import zipfile

PINNED_VERSION = "0.1.5rc1"
PYPI_JSON_URL = "https://pypi.org/pypi/deepseek-harness-runtime-bin/json"

TARGET_DIR = "/opt/yunke-dsh/runtime"
TARGET_BIN_MAIN = os.path.join(TARGET_DIR, "deepseek-harness-sdk-runtime-linux-arm64")
TARGET_BIN_RG = os.path.join(TARGET_DIR, "deepseek-harness-sdk-runtime-linux-arm64-rg")
DEFAULT_SYMLINK = "/usr/local/bin/dsh"
DEFAULT_KEY_FILE = "/root/.dsh/key"

WHL_BIN_MAIN = "deepseek_harness_runtime/runtime/deepseek-harness-sdk-runtime-linux-arm64"
WHL_BIN_RG = "deepseek_harness_runtime/runtime/deepseek-harness-sdk-runtime-linux-arm64-rg"

STDOUT_LIMIT = 15000


class Refused(ValueError):
    """稳定的错误码 + 少量非敏感细节，供上层分类处理。"""

    def __init__(self, code, **details):
        super().__init__(code)
        self.code = code
        self.details = details


def require(condition, code, **details):
    if not condition:
        raise Refused(code, **details)


def units(text):
    """UTF-16 code units: matches Kotlin bridge truncate budget."""
    return len(text.encode('utf-16-le')) // 2


def response_line(result):
    line = json.dumps({'ok': True, **result}, ensure_ascii=False)
    if units(line) > STDOUT_LIMIT:
        line = json.dumps({'ok': False, 'code': 'OUTPUT_TOO_LARGE',
                           'hint': 'provision output exceeded stdout limit'})
    return line


def log(msg):
    print(msg, file=sys.stderr, flush=True)


def handle_status(args):
    runtime_bin = (args.get('runtime_bin') or DEFAULT_SYMLINK).strip()
    key_file = (args.get('key_file') or DEFAULT_KEY_FILE).strip()

    installed = False
    version = None
    main_exists = os.path.isfile(TARGET_BIN_MAIN) and os.access(TARGET_BIN_MAIN, os.X_OK)
    rg_exists = os.path.isfile(TARGET_BIN_RG) and os.access(TARGET_BIN_RG, os.X_OK)
    bin_exists = (os.path.isfile(runtime_bin) or os.path.islink(runtime_bin)) and os.access(runtime_bin, os.X_OK)

    if bin_exists or (main_exists and rg_exists):
        exec_path = runtime_bin if bin_exists else TARGET_BIN_MAIN
        try:
            proc = subprocess.run([exec_path, '--version'],
                                  stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE,
                                  timeout=10,
                                  text=True)
            if proc.returncode == 0:
                version = proc.stdout.strip()
                installed = True
        except Exception as err:
            log("Failed to execute %s --version: %s" % (exec_path, err))

    key_exists = False
    try:
        key_exists = os.path.isfile(key_file)
    except Exception:
        pass

    return {
        'installed': installed,
        'version': version,
        'key_exists': key_exists,
        'runtime_path': runtime_bin,
        'key_file': key_file,
    }


def fetch_pypi_wheel_info(target_version):
    try:
        req = urllib.request.Request(PYPI_JSON_URL, headers={'User-Agent': 'yunke-eta-dsh/1.0'})
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode('utf-8'))
    except Exception as err:
        raise Refused('PYPI_METADATA_FETCH_FAILED', hint=str(err))

    releases = data.get('releases', {})
    files = releases.get(target_version)
    if not files:
        for ver_key, ver_files in releases.items():
            if ver_key.lower() == target_version.lower():
                files = ver_files
                break
    if not files:
        raise Refused('VERSION_NOT_FOUND', version=target_version)

    for f in files:
        fn = f.get('filename', '')
        if fn.endswith('.whl') and 'aarch64' in fn:
            url = f.get('url')
            if url:
                return url, fn
    raise Refused('WHEEL_NOT_FOUND', version=target_version)


def handle_install(args):
    target_version = (args.get('version') or PINNED_VERSION).strip()
    force = bool(args.get('force', False))
    runtime_bin = (args.get('runtime_bin') or DEFAULT_SYMLINK).strip()

    # 幂等性检测：若已安装且未要求 force，直接返回
    if not force:
        status = handle_status(args)
        if status['installed'] and status['version']:
            return {
                'installed': True,
                'already_installed': True,
                'version': status['version'],
                'runtime_path': status['runtime_path'],
                'message': 'Runtime already installed'
            }

    # 1. 从 PyPI 取 wheel 地址
    wheel_url, wheel_filename = fetch_pypi_wheel_info(target_version)
    log("Resolved wheel %s at %s" % (wheel_filename, wheel_url))

    # 2. 下载到临时文件
    tmp_fd, tmp_path = tempfile.mkstemp(prefix='dsh_wheel_', suffix='.whl')
    os.close(tmp_fd)
    try:
        log("Downloading %s to %s..." % (wheel_filename, tmp_path))
        try:
            req = urllib.request.Request(wheel_url, headers={'User-Agent': 'yunke-eta-dsh/1.0'})
            with urllib.request.urlopen(req, timeout=300) as resp, open(tmp_path, 'wb') as out_f:
                shutil.copyfileobj(resp, out_f, length=1024 * 1024)
        except Exception as err:
            raise Refused('DOWNLOAD_FAILED', hint=str(err))

        # 3. 用 zipfile 抽出主二进制和伴生二进制
        log("Extracting binaries to %s..." % TARGET_DIR)
        try:
            with zipfile.ZipFile(tmp_path, 'r') as zf:
                namelist = set(zf.namelist())
                if WHL_BIN_MAIN not in namelist or WHL_BIN_RG not in namelist:
                    raise Refused('WHEEL_STRUCTURE_INVALID',
                                  hint='Required binaries not found in wheel archive')

                os.makedirs(TARGET_DIR, exist_ok=True)
                for src_name, dst_path in [(WHL_BIN_MAIN, TARGET_BIN_MAIN), (WHL_BIN_RG, TARGET_BIN_RG)]:
                    tmp_dst = dst_path + f'.tmp.{os.getpid()}'
                    with zf.open(src_name) as src, open(tmp_dst, 'wb') as dst:
                        shutil.copyfileobj(src, dst, length=1024 * 1024)
                    os.chmod(tmp_dst, 0o755)
                    os.replace(tmp_dst, dst_path)
        except Refused:
            raise
        except zipfile.BadZipFile as err:
            raise Refused('WHEEL_STRUCTURE_INVALID', hint='Bad zip file: %s' % err)
        except Exception as err:
            raise Refused('EXTRACT_FAILED', hint=str(err))

        # 4. chmod 0o755
        try:
            os.chmod(TARGET_BIN_MAIN, 0o755)
            os.chmod(TARGET_BIN_RG, 0o755)
        except Exception as err:
            raise Refused('CHMOD_FAILED', hint=str(err))

        # 5. 建软链
        log("Creating symlink %s -> %s..." % (runtime_bin, TARGET_BIN_MAIN))
        try:
            symlink_dir = os.path.dirname(runtime_bin)
            os.makedirs(symlink_dir, exist_ok=True)
            tmp_symlink = os.path.join(symlink_dir, f".dsh_symlink_{uuid.uuid4().hex}")
            os.symlink(TARGET_BIN_MAIN, tmp_symlink)
            os.replace(tmp_symlink, runtime_bin)
        except Exception as err:
            try:
                if os.path.islink(runtime_bin) or os.path.exists(runtime_bin):
                    os.unlink(runtime_bin)
                os.symlink(TARGET_BIN_MAIN, runtime_bin)
            except Exception as fallback_err:
                raise Refused('SYMLINK_FAILED', hint=str(fallback_err))

        # 验证安装
        try:
            proc = subprocess.run([runtime_bin, '--version'],
                                  stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE,
                                  timeout=15,
                                  text=True)
            if proc.returncode != 0:
                raise Refused('VERIFICATION_FAILED',
                              hint='dsh exited with %d: %s' % (proc.returncode, proc.stderr.strip()))
            installed_version = proc.stdout.strip()
        except Refused:
            raise
        except Exception as err:
            raise Refused('VERIFICATION_FAILED', hint=str(err))

        return {
            'installed': True,
            'already_installed': False,
            'version': installed_version,
            'runtime_path': runtime_bin,
            'message': 'Successfully installed dsh runtime (%s)' % installed_version
        }

    finally:
        # 6. 删除临时文件
        if os.path.exists(tmp_path):
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def execute(args):
    action = args.get('action')
    if action == 'status':
        return handle_status(args)
    elif action == 'install':
        return handle_install(args)
    else:
        raise Refused('UNKNOWN_ACTION', action=str(action))


if __name__ == '__main__':
    try:
        require(len(sys.argv) > 1, 'INVALID_ARGUMENTS', hint='Missing argument')
        args = json.loads(sys.argv[1])
        require(isinstance(args, dict), 'INVALID_ARGUMENTS', hint='Arguments must be a JSON object')
        print(response_line(execute(args)))
    except Exception as error:
        code = error.code if isinstance(error, Refused) else (
            str(error) if isinstance(error, ValueError) and re.fullmatch(r'[A-Z_0-9]+', str(error))
            else 'PROVISION_FAILED'
        )
        details = error.details if isinstance(error, Refused) else {'message': str(error)}
        line = json.dumps({'ok': False, 'code': code, **details}, ensure_ascii=False)
        if units(line) > STDOUT_LIMIT:
            line = json.dumps({'ok': False, 'code': 'OUTPUT_TOO_LARGE'}, ensure_ascii=False)
        print(line)
