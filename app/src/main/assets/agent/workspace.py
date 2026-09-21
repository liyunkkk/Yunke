"""Runtime-owned workspace operations. No model-supplied shell or Python is executed."""
import json
import os
from pathlib import Path
import re
import subprocess
import shutil
import sys
import uuid
import time

LIMIT = 65536

def require(condition, code):
    if not condition:
        raise ValueError(code)

def git(root, *args):
    env = os.environ.copy()
    for key in list(env):
        if key.startswith('GIT_'):
            del env[key]
    env['GIT_TERMINAL_PROMPT'] = '0'
    p = subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgsign=false',
                        '-c', 'core.fsmonitor=false', '-c', 'diff.external=', '-C', str(root), *args],
                       env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=25)
    require(p.returncode == 0, 'GIT_OPERATION_FAILED')
    require(len(p.stdout) <= 2 * 1024 * 1024, 'WORKSPACE_OUTPUT_TOO_LARGE')
    return p.stdout.decode('utf-8', errors='replace').strip()

def project_path(raw):
    p = Path(raw)
    require(p.is_absolute() and len(p.parts) == 3 and p.parts[1] == 'workspace', 'PROJECT_MUST_BE_WORKSPACE_CHILD')
    require(p.name not in ('mounts', 'browser', 'offloads') and not p.is_symlink(), 'INVALID_PROJECT')
    require(p.is_dir() and str(p.resolve()) == str(p), 'INVALID_PROJECT')
    require(git(p, 'rev-parse', '--show-toplevel') == str(p), 'PROJECT_MUST_BE_GIT_ROOT')
    return p

def internal(root, rel):
    p = root / rel
    cur = root
    for part in Path(rel).parts:
        cur = cur / part
        require(not cur.is_symlink(), 'WORKSPACE_SYMLINK_DENIED')
    return p

def record_path(root, task):
    require(re.fullmatch(r'[a-f0-9]{32}', task or '') is not None, 'INVALID_WORKSPACE_ID')
    return internal(root, '.agent/results/' + task + '.json')

def save(root, record):
    p = record_path(root, record['id'])
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_suffix('.tmp')
    require(not tmp.is_symlink(), 'WORKSPACE_SYMLINK_DENIED')
    tmp.write_text(json.dumps(record, ensure_ascii=False), encoding='utf-8')
    tmp.replace(p)

def load(root, task):
    p = record_path(root, task)
    require(p.is_file(), 'WORKSPACE_NOT_FOUND')
    record = json.loads(p.read_text())
    require(record['id'] == task, 'INVALID_WORKSPACE_RECORD')
    return record

def tree_path(root, record):
    return internal(root, '.agent/worktrees/' + record['id'])

def safe_file(tree, relative):
    path = Path(relative)
    require(not path.is_absolute() and bool(path.parts) and all(x not in ('..', '.git', '.agent', '.gitattributes', '.gitmodules') for x in path.parts), 'WORKSPACE_PATH_DENIED')
    p = internal(tree, path)
    require(p != tree, 'WORKSPACE_PATH_DENIED')
    if p.exists():
        require(p.is_file() or p.is_dir(), 'WORKSPACE_SPECIAL_FILE_DENIED')
        if p.is_file():
            require(p.stat().st_nlink == 1, 'WORKSPACE_HARDLINK_DENIED')
    return p

def clean(root):
    # .agent is runtime-owned, even before a user's ignore rules have been configured.
    return not git(root, 'status', '--porcelain', '--untracked-files=all', '--', '.', ':(exclude).agent')

def summary(root, record):
    out = dict(record)
    tree = tree_path(root, record)
    out['path'] = str(tree)
    if tree.exists():
        diff = git(tree, 'diff', '--no-ext-diff', '--no-textconv', '--stat', record['base'])
        out['diff_stat'] = diff[:2000]
    return out

def page(text, args, key):
    offset, limit = int(args.get('offset', 0)), int(args.get('limit', 4000))
    require(offset >= 0 and 1 <= limit <= 4000, 'INVALID_PAGE')
    end = min(len(text), offset + limit)
    return {key: text[offset:end], 'next_offset': end if end < len(text) else None, 'total_chars': len(text)}

def execute(args):
    root = project_path(args['project'])
    # Cross-process lock serializes file writes, sealing, review, integration and cleanup.
    import fcntl
    lock = internal(root, '.agent/workspace.lock')
    lock.parent.mkdir(parents=True, exist_ok=True)
    with open(lock, 'a') as handle:
        fcntl.flock(handle, fcntl.LOCK_EX)
        return locked(root, args)

def locked(root, args):
    action = args['action']
    if action == 'prepare':
        require(clean(root), 'PROJECT_HAS_UNCOMMITTED_CHANGES')
        require(shutil.disk_usage(root).free >= 512 * 1024 * 1024, 'WORKSPACE_DISK_SPACE_LOW')
        tasks = internal(root, '.agent/worktrees')
        tasks.mkdir(parents=True, exist_ok=True)
        task = uuid.uuid4().hex
        base = git(root, 'rev-parse', 'HEAD')
        record = {'id': task, 'base': base, 'state': 'editing', 'reviewed': False, 'lease_until': time.time() + 420}
        tree = tree_path(root, record)
        git(root, 'worktree', 'add', '--detach', str(tree), base)
        save(root, record)
        return summary(root, record)
    if action == 'list':
        folder = internal(root, '.agent/results')
        return {'workspaces': [json.loads(p.read_text()) for p in sorted(folder.glob('*.json'))[:50]
                               if re.fullmatch(r'[a-f0-9]{32}\.json', p.name) and not p.is_symlink()]}
    record = load(root, args['workspace_id'])
    tree = tree_path(root, record)
    if action == 'inspect':
        if record['state'] in ('editing', 'reviewing') and time.time() > record.get('lease_until', 0):
            record['state'] = 'failed' if record['state'] == 'editing' else 'ready'
            record['reviewed'] = False
            save(root, record)
        return summary(root, record)
    if action in ('read', 'list_files', 'write', 'delete', 'diff'):
        require(record['state'] in ('editing', 'reviewing', 'ready', 'failed'), 'WORKSPACE_NOT_AVAILABLE')
        if action == 'diff':
            text = git(tree, 'diff', '--no-ext-diff', '--no-textconv', record['base'])
            return page(text, args, 'diff')
        if action == 'list_files':
            return page(git(tree, 'ls-files', '--cached', '--others', '--exclude-standard'), args, 'files')
        p = safe_file(tree, args['path'])
        if action == 'read':
            require(p.is_file() and p.stat().st_size <= LIMIT, 'FILE_MISSING_OR_TOO_LARGE')
            return page(p.read_text(encoding='utf-8'), args, 'content')
        require(record['state'] == 'editing' and time.time() <= record.get('lease_until', 0), 'WORKSPACE_FROZEN')
        if action == 'write':
            text = args['content']
            require(isinstance(text, str) and len(text.encode()) <= LIMIT, 'FILE_TOO_LARGE')
            written = record.get('written_bytes', 0) + len(text.encode())
            require(written <= 16 * 1024 * 1024, 'WORKSPACE_WRITE_BUDGET')
            record['written_bytes'] = written
            save(root, record)
            p.parent.mkdir(parents=True, exist_ok=True)
            # O_NOFOLLOW plus symlink/component checks; no child has an arbitrary shell.
            fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o644)
            with os.fdopen(fd, 'w', encoding='utf-8') as out: out.write(text)
        else:
            require(p.is_file(), 'FILE_NOT_FOUND')
            p.unlink()
        return {'changed': args['path']}
    if action == 'seal':
        require(record['state'] == 'editing' and time.time() <= record.get('lease_until', 0), 'WORKSPACE_NOT_EDITING')
        git(tree, 'add', '-A', '--', '.', ':(exclude).agent')
        if git(tree, 'diff', '--cached', '--name-only'):
            git(tree, '-c', 'user.name=Eta Agent', '-c', 'user.email=agent@localhost', 'commit', '-m', 'Agent implementation')
        record.update(state='ready', commit=git(tree, 'rev-parse', 'HEAD'), reviewed=False)
        save(root, record)
    elif action == 'fail':
        if record['state'] in ('editing', 'ready'):
            record['state'] = 'failed'
            save(root, record)
    elif action == 'begin_review':
        require(record['state'] == 'ready' and clean(tree), 'WORKSPACE_NOT_READY')
        require(git(tree, 'rev-parse', 'HEAD') == record['commit'], 'WORKSPACE_CHANGED')
        record.update(state='reviewing', reviewed=False, lease_until=time.time() + 420)
        save(root, record)
    elif action == 'end_review':
        # Cancellation may arrive just after review marked the workspace ready.
        require(record['state'] in ('reviewing', 'ready'), 'WORKSPACE_NOT_REVIEWING')
        record.update(state='ready', reviewed=False)
        save(root, record)
    elif action == 'review':
        require(record['state'] == 'reviewing' and clean(tree), 'WORKSPACE_NOT_READY')
        require(git(tree, 'rev-parse', 'HEAD') == record['commit'], 'WORKSPACE_CHANGED')
        record.update(state='ready', reviewed=True)
        save(root, record)
    elif action == 'merge':
        require(record['state'] == 'ready' and record['reviewed'], 'REVIEW_REQUIRED')
        require(clean(root) and clean(tree), 'UNCOMMITTED_CHANGES')
        require(git(root, 'rev-parse', 'HEAD') == record['base'], 'PROJECT_MOVED_REVIEW_AGAIN')
        require(git(tree, 'rev-parse', 'HEAD') == record['commit'], 'WORKSPACE_CHANGED')
        git(root, '-c', 'merge.autostash=false', 'merge', '--ff-only', record['commit'])
        record['state'] = 'merged'
        save(root, record)
        git(root, 'worktree', 'remove', str(tree))
    elif action == 'discard':
        require(record['state'] in ('ready', 'failed', 'merged', 'discarded'), 'WORKSPACE_IN_USE')
        if tree.exists(): git(root, 'worktree', 'remove', '--force', str(tree))
        record['state'] = 'discarded'
        save(root, record)
    else:
        raise ValueError('UNKNOWN_WORKSPACE_ACTION')
    return summary(root, record)

if __name__ == '__main__':
    try:
        result = execute(json.loads(sys.argv[1]))
        print(json.dumps({'ok': True, **result}, ensure_ascii=False))
    except Exception as error:
        code = str(error) if isinstance(error, ValueError) and re.fullmatch(r'[A-Z_]+', str(error)) else 'WORKSPACE_OPERATION_FAILED'
        print(json.dumps({'ok': False, 'code': code}))
