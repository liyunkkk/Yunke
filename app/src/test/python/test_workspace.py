import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[2] / 'main/assets/agent/workspace.py'
spec = importlib.util.spec_from_file_location('workspace', SOURCE)
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)

class WorkspaceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / 'project'
        self.root.mkdir()
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        w.git(self.root, 'config', 'user.name', 'Test')
        w.git(self.root, 'config', 'user.email', 'test@example.com')
        (self.root/'main.txt').write_text('original')
        w.git(self.root, 'add', '.')
        w.git(self.root, 'commit', '-m', 'initial')
    def tearDown(self): self.temp.cleanup()
    def op(self, action, record=None, **kwargs):
        args = {'action':action, **kwargs}
        if record: args['workspace_id'] = record['id']
        return w.locked(self.root, args)
    def test_dynamic_agents_can_prepare_more_than_eight_isolated_workspaces(self):
        records = [self.op('prepare') for _ in range(9)]
        self.assertEqual(9, len({record['id'] for record in records}))
        self.op('write', records[-1], path='main.txt', content='ninth workspace')
        self.assertEqual('original', self.op('read', records[0], path='main.txt')['content'])
        for record in records:
            self.op('fail', record)
            self.op('discard', record)
        self.assertEqual(1, len(w.git(self.root, 'worktree', 'list').splitlines()))
    def test_isolation_seal_review_merge_and_cleanup(self):
        record = self.op('prepare')
        self.op('write', record, path='main.txt', content='new')
        self.op('write', record, path='nested/new.txt', content='new file')
        self.assertEqual('original', (self.root/'main.txt').read_text())
        self.op('seal', record)
        with self.assertRaisesRegex(ValueError, 'WORKSPACE_FROZEN'):
            self.op('write', record, path='main.txt', content='late')
        with self.assertRaisesRegex(ValueError, 'REVIEW_REQUIRED'): self.op('merge', record)
        self.op('begin_review', record)
        self.assertIn('new file', self.op('diff', record)['diff'])
        with self.assertRaisesRegex(ValueError, 'WORKSPACE_IN_USE'): self.op('discard', record)
        self.op('review', record)
        self.assertEqual('merged', self.op('merge', record)['state'])
        self.assertEqual('new', (self.root/'main.txt').read_text())
        self.assertFalse(Path(record['path']).exists())
        self.assertEqual(1, len(w.git(self.root, 'worktree', 'list').splitlines()))
    def test_cancel_after_review_completion_invalidates_review_marker(self):
        record = self.op('prepare')
        self.op('seal', record)
        self.op('begin_review', record)
        self.op('review', record)
        self.op('end_review', record)
        self.assertFalse(w.load(self.root, record['id'])['reviewed'])
        with self.assertRaisesRegex(ValueError, 'REVIEW_REQUIRED'):
            self.op('merge', record)
    def test_path_symlink_hardlink_and_metadata_escape(self):
        record = self.op('prepare'); tree=Path(record['path'])
        for path in ['../outside', '/tmp/outside', '.git', '.git/config', '.agent/other', '.gitattributes']:
            with self.assertRaises(ValueError): self.op('write', record, path=path, content='bad')
        (tree/'escape').symlink_to(self.root)
        with self.assertRaisesRegex(ValueError, 'SYMLINK'): self.op('read', record, path='escape/main.txt')
        os.link(self.root/'main.txt', tree/'hardlink')
        with self.assertRaisesRegex(ValueError, 'HARDLINK'): self.op('write', record, path='hardlink', content='bad')
        self.assertEqual('original', (self.root/'main.txt').read_text())
    def test_dirty_project_and_moved_base_are_rejected(self):
        (self.root/'main.txt').write_text('dirty')
        with self.assertRaisesRegex(ValueError, 'UNCOMMITTED'): self.op('prepare')
        w.git(self.root, 'restore', '.')
        record=self.op('prepare'); self.op('seal', record); self.op('begin_review', record); self.op('review', record)
        w.git(self.root, 'commit', '--allow-empty', '-m', 'parent moved')
        with self.assertRaisesRegex(ValueError, 'PROJECT_MOVED'): self.op('merge', record)
    def test_other_project_cannot_retrieve_workspace(self):
        record=self.op('prepare')
        other=Path(self.temp.name)/'other'; other.mkdir()
        with self.assertRaisesRegex(ValueError, 'WORKSPACE_NOT_FOUND'): w.load(other, record['id'])
        with self.assertRaisesRegex(ValueError, 'INVALID_WORKSPACE_ID'): w.load(other, '../project')
    def test_failed_task_keeps_changes_until_explicit_discard(self):
        record=self.op('prepare'); self.op('write', record, path='main.txt', content='partial')
        self.op('fail', record)
        self.assertEqual('partial', self.op('read', record, path='main.txt')['content'])
        with self.assertRaisesRegex(ValueError, 'REVIEW_REQUIRED'): self.op('merge', record)
        self.op('discard', record)
        self.assertFalse(Path(record['path']).exists())
    def test_expired_task_is_recoverable_without_deleting_changes(self):
        record=self.op('prepare'); saved=w.load(self.root,record['id']); saved['lease_until']=0; w.save(self.root,saved)
        self.assertEqual('failed',self.op('inspect',record)['state'])
        self.assertTrue(Path(record['path']).exists())
        self.op('discard',record)
    def test_renew_keeps_workspace_and_cannot_revive_finished_lease(self):
        record=self.op('prepare')
        self.op('write',record,path='main.txt',content='preserved')
        saved=w.load(self.root,record['id']); before=saved['lease_until']
        self.op('renew',record)
        self.assertGreaterEqual(w.load(self.root,record['id'])['lease_until'], before)
        self.assertEqual('preserved', self.op('read',record,path='main.txt')['content'])
        self.op('seal',record)
        with self.assertRaisesRegex(ValueError,'WORKSPACE_LEASE_LOST'): self.op('renew',record)
    def test_pagination_and_utf8_budget(self):
        record=self.op('prepare')
        self.op('write',record,path='text.txt',content='中文'*30)
        result=self.op('read',record,path='text.txt',offset=2,limit=5)
        self.assertEqual(7,result['next_offset']); self.assertEqual(5,len(result['content']))
        with self.assertRaisesRegex(ValueError,'FILE_TOO_LARGE'): self.op('write',record,path='large.txt',content='中'*65536)
    def test_write_budget_does_not_modify_destination(self):
        record=self.op('prepare'); saved=w.load(self.root,record['id']); saved['written_bytes']=16*1024*1024; w.save(self.root,saved)
        with self.assertRaisesRegex(ValueError,'WORKSPACE_WRITE_BUDGET'):
            self.op('write',record,path='main.txt',content='too much')
        self.assertEqual('original',self.op('read',record,path='main.txt')['content'])
    def test_project_validation_rejects_workspace_root_and_nested(self):
        for path in ['/workspace','/workspace/Eta/nested','/tmp/project','relative']:
            with self.assertRaises(ValueError): w.project_path(path)

if __name__ == '__main__': unittest.main()
