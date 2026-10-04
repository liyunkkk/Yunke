import importlib.util
import os
from pathlib import Path
import subprocess
import json
import tempfile
import time
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
        (self.root / 'main.txt').write_text('original')
        w.git(self.root, 'add', '.')
        w.git(self.root, 'commit', '-m', 'initial')

    def tearDown(self):
        self.temp.cleanup()

    def op(self, action, record=None, **kwargs):
        args = {'action': action, **kwargs}
        if record:
            args['workspace_id'] = record['id']
        return w.locked(self.root, args)

    def refusal(self, action, record=None, **kwargs):
        try:
            self.op(action, record, **kwargs)
        except w.Refused as error:
            return error
        self.fail(f'{action} should have been refused')

    def put_record(self, task, **fields):
        record = {'id': task, 'state': 'editing'}
        record.update(fields)
        w.save(self.root, record)
        return record

    def test_authorized_list_pages_beyond_fifty_without_losing_owned_records(self):
        ids = [f'{i:032x}' for i in range(61)]
        for task in ids:
            w.save(self.root, {'id': task, 'state': 'ready'})
        first = self.op('list', workspace_ids=ids, limit=50)
        self.assertEqual(50, len(first['workspaces']))
        self.assertEqual(50, first['next_offset'])
        self.assertEqual(61, first['total_count'])
        last = self.op('list', workspace_ids=ids, offset=first['next_offset'], limit=50)
        self.assertEqual(ids[50:], [row['id'] for row in last['workspaces']])
        self.assertIsNone(last['next_offset'])
        self.assertFalse(last['truncated'])
        for offset, limit in [(-1, 50), (0, 0), (0, 51), (True, 50), (0, 1.5)]:
            with self.assertRaisesRegex(ValueError, 'INVALID_PAGE'):
                self.op('list', workspace_ids=ids, offset=offset, limit=limit)

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
        self.assertEqual('original', (self.root / 'main.txt').read_text())
        self.op('seal', record)
        with self.assertRaisesRegex(ValueError, 'WORKSPACE_FROZEN'):
            self.op('write', record, path='main.txt', content='late')
        with self.assertRaisesRegex(ValueError, 'REVIEW_REQUIRED'):
            self.op('merge', record)
        self.op('begin_review', record)
        self.assertIn('new file', self.op('diff', record)['diff'])
        with self.assertRaisesRegex(ValueError, 'WORKSPACE_IN_USE'):
            self.op('discard', record)
        self.op('review', record)
        self.assertEqual('merged', self.op('merge', record)['state'])
        self.assertEqual('new', (self.root / 'main.txt').read_text())
        self.assertFalse(Path(record['path']).exists())
        self.assertEqual(1, len(w.git(self.root, 'worktree', 'list').splitlines()))

    def test_cancel_after_review_completion_invalidates_review_marker(self):
        record = self.op('prepare')
        self.op('write', record, path='main.txt', content='original changed')
        self.op('seal', record)
        self.op('begin_review', record)
        self.op('review', record)
        self.op('end_review', record)
        self.assertFalse(w.load(self.root, record['id'])['reviewed'])
        with self.assertRaisesRegex(ValueError, 'REVIEW_REQUIRED'):
            self.op('merge', record)

    def test_path_symlink_hardlink_and_metadata_escape(self):
        record = self.op('prepare')
        tree = Path(record['path'])
        for path in ['../outside', '/tmp/outside', '.git', '.git/config', '.agent/other', '.gitattributes']:
            with self.assertRaises(ValueError):
                self.op('write', record, path=path, content='bad')
        (tree / 'escape').symlink_to(self.root)
        with self.assertRaisesRegex(ValueError, 'SYMLINK'):
            self.op('read', record, path='escape/main.txt')
        os.link(self.root / 'main.txt', tree / 'hardlink')
        with self.assertRaisesRegex(ValueError, 'HARDLINK'):
            self.op('write', record, path='hardlink', content='bad')
        self.assertEqual('original', (self.root / 'main.txt').read_text())

    def test_dirty_project_and_moved_base_are_rejected(self):
        (self.root / 'main.txt').write_text('dirty')
        with self.assertRaisesRegex(ValueError, 'UNCOMMITTED'):
            self.op('prepare')
        w.git(self.root, 'restore', '.')
        record = self.op('prepare')
        self.op('write', record, path='main.txt', content='original changed')
        self.op('seal', record)
        self.op('begin_review', record)
        self.op('review', record)
        w.git(self.root, 'commit', '--allow-empty', '-m', 'parent moved')
        with self.assertRaisesRegex(ValueError, 'PROJECT_MOVED'):
            self.op('merge', record)

    def test_other_project_cannot_retrieve_workspace(self):
        record = self.op('prepare')
        other = Path(self.temp.name) / 'other'
        other.mkdir()
        with self.assertRaisesRegex(ValueError, 'WORKSPACE_NOT_FOUND'):
            w.load(other, record['id'])
        with self.assertRaisesRegex(ValueError, 'INVALID_WORKSPACE_ID'):
            w.load(other, '../project')

    def test_failed_task_keeps_changes_until_explicit_discard(self):
        record = self.op('prepare')
        self.op('write', record, path='main.txt', content='partial')
        self.op('fail', record)
        self.assertEqual('partial', self.op('read', record, path='main.txt')['content'])
        # A failed worktree is not review-ready: name the real blocker instead of REVIEW_REQUIRED.
        refused = self.refusal('merge', record)
        self.assertEqual('WORKSPACE_NOT_READY', str(refused))
        self.assertEqual('failed', refused.details['state'])
        self.assertIn('UNCOMMITTED_CHANGES', refused.details['merge_blocked_by'])
        self.assertEqual(['inspect', 'discard'], refused.details['allowed_actions'])
        self.op('discard', record)
        self.assertFalse(Path(record['path']).exists())

    def test_inspect_reports_merge_blockers_and_preserves_uncommitted_changes(self):
        record = self.op('prepare')
        self.op('write', record, path='main.txt', content='partial')
        view = self.op('inspect', record)
        self.assertEqual('editing', view['state'])
        self.assertTrue(view['tree_exists'])
        self.assertTrue(view['uncommitted_changes'])
        self.assertFalse(view['project_uncommitted_changes'])
        self.assertFalse(view['merge_ready'])
        self.assertEqual(['WORKSPACE_NOT_READY', 'UNCOMMITTED_CHANGES'], view['merge_blocked_by'])
        self.assertEqual(['inspect'], view['allowed_actions'])
        self.assertEqual('partial', self.op('read', record, path='main.txt')['content'])
        self.assertEqual('partial', (Path(record['path']) / 'main.txt').read_text())

    def test_failed_worktree_offers_inspect_discard_or_a_new_task(self):
        record = self.op('prepare')
        self.op('write', record, path='main.txt', content='partial')
        self.op('fail', record)
        view = self.op('inspect', record)
        self.assertEqual('failed', view['state'])
        self.assertFalse(view['merge_ready'])
        self.assertIn('WORKSPACE_NOT_READY', view['merge_blocked_by'])
        self.assertEqual(['inspect', 'discard'], view['allowed_actions'])
        self.assertIn('discard', view['next_step'])
        self.assertIn('重新委派', view['next_step'])
        self.assertNotIn('replace_task_id', view['allowed_actions'])
        # Uncommitted work survives the refused merge/inspect and is only removed by discard.
        self.assertEqual('partial', (Path(record['path']) / 'main.txt').read_text())
        self.op('discard', record)

    def test_ready_workspace_becomes_merge_ready_only_after_review(self):
        record = self.op('prepare')
        self.op('write', record, path='main.txt', content='new')
        sealed = self.op('seal', record)
        self.assertEqual('ready', sealed['state'])
        self.assertFalse(sealed['merge_ready'])
        self.assertEqual(['REVIEW_REQUIRED'], sealed['merge_blocked_by'])
        self.assertEqual(['inspect', 'discard'], sealed['allowed_actions'])
        self.op('begin_review', record)
        self.assertEqual(['WORKSPACE_NOT_READY'], self.op('inspect', record)['merge_blocked_by'])
        self.op('review', record)
        reviewed = self.op('inspect', record)
        self.assertEqual([], reviewed['merge_blocked_by'])
        self.assertTrue(reviewed['merge_ready'])
        self.assertEqual(['inspect', 'merge', 'discard'], reviewed['allowed_actions'])
        self.assertTrue(reviewed['head_matches_commit'])
        merged = self.op('merge', record)
        self.assertEqual('merged', merged['state'])
        self.assertFalse(merged['tree_exists'])
        self.assertIsNone(merged['committed_head'])

    def test_merge_refusal_names_the_blocker_and_keeps_both_sides(self):
        record = self.op('prepare')
        self.op('write', record, path='main.txt', content='new')
        self.op('seal', record)
        self.op('begin_review', record)
        self.op('review', record)
        (self.root / 'main.txt').write_text('parent edit')
        refused = self.refusal('merge', record)
        self.assertEqual('UNCOMMITTED_CHANGES', str(refused))
        self.assertTrue(refused.details['project_uncommitted_changes'])
        self.assertFalse(refused.details['uncommitted_changes'])
        self.assertEqual(['UNCOMMITTED_CHANGES'], refused.details['merge_blocked_by'])
        self.assertNotIn('merge', refused.details['allowed_actions'])
        self.assertEqual('parent edit', (self.root / 'main.txt').read_text())
        self.assertEqual('new', (Path(record['path']) / 'main.txt').read_text())

    def reviewed_workspace(self):
        record = self.op('prepare')
        self.op('write', record, path='main.txt', content='child commit')
        self.op('write', record, path='receipt-marker.txt', content=record['id'])
        self.op('seal', record)
        self.op('begin_review', record)
        self.op('review', record)
        return record

    def assert_merge_refused_without_parent_mutation(self, record, code):
        parent_head = w.git(self.root, 'rev-parse', 'HEAD')
        parent_file = (self.root / 'main.txt').read_bytes()
        parent_status = w.git(self.root, 'status', '--porcelain', '--', '.', ':(exclude).agent')
        view = self.op('inspect', record)
        self.assertFalse(view['merge_ready'])
        self.assertIn(code, view['merge_blocked_by'])
        self.assertNotIn('merge', view['allowed_actions'])
        refused = self.refusal('merge', record)
        self.assertEqual(code, str(refused))
        self.assertIn(code, refused.details['merge_blocked_by'])
        self.assertEqual(parent_head, w.git(self.root, 'rev-parse', 'HEAD'))
        self.assertEqual(parent_file, (self.root / 'main.txt').read_bytes())
        self.assertEqual(parent_status, w.git(self.root, 'status', '--porcelain', '--', '.', ':(exclude).agent'))
        self.assertEqual('ready', w.load(self.root, record['id'])['state'])
        return view

    def test_missing_tree_blocks_merge_before_any_parent_update(self):
        record = self.reviewed_workspace()
        tree = Path(record['path'])
        w.git(self.root, 'worktree', 'remove', str(tree))
        view = self.assert_merge_refused_without_parent_mutation(record, 'WORKSPACE_TREE_MISSING')
        self.assertFalse(view['tree_exists'])
        self.assertFalse(view['head_matches_commit'])

    def test_missing_git_marker_does_not_inherit_parent_repository(self):
        record = self.reviewed_workspace()
        tree = Path(record['path'])
        marker = (tree / '.git').read_bytes()
        try:
            (tree / '.git').unlink()
            view = self.assert_merge_refused_without_parent_mutation(record, 'WORKSPACE_TREE_INVALID')
            self.assertFalse(view['tree_exists'])
            self.assertIsNone(view['committed_head'])
            self.assertEqual('child commit', (tree / 'main.txt').read_text())
        finally:
            (tree / '.git').write_bytes(marker)

    def test_missing_or_invalid_commit_is_an_explicit_safe_refusal(self):
        record = self.reviewed_workspace()
        saved = w.load(self.root, record['id'])
        for value in (None, '', 'HEAD', 'bad-commit', '0' * 40):
            with self.subTest(value=value):
                corrupt = dict(saved)
                if value is None:
                    corrupt.pop('commit')
                else:
                    corrupt['commit'] = value
                w.save(self.root, corrupt)
                self.assert_merge_refused_without_parent_mutation(record, 'WORKSPACE_COMMIT_INVALID')
                self.assertEqual('child commit', (Path(record['path']) / 'main.txt').read_text())
        w.save(self.root, saved)

    def test_missing_or_invalid_base_is_an_explicit_safe_refusal(self):
        record = self.reviewed_workspace()
        saved = w.load(self.root, record['id'])
        for value in (None, '', 'HEAD', 'bad-base', '0' * 40):
            with self.subTest(value=value):
                corrupt = dict(saved)
                if value is None:
                    corrupt.pop('base')
                else:
                    corrupt['base'] = value
                w.save(self.root, corrupt)
                self.assert_merge_refused_without_parent_mutation(record, 'WORKSPACE_BASE_INVALID')
                self.assertEqual('child commit', (Path(record['path']) / 'main.txt').read_text())
        w.save(self.root, saved)

    def test_terminal_workspace_records_only_offer_read_only_inspection(self):
        record = self.reviewed_workspace()
        merged = self.op('merge', record)
        self.assertEqual(['inspect'], merged['allowed_actions'])
        record = self.reviewed_workspace()
        discarded = self.op('discard', record)
        self.assertEqual(['inspect'], discarded['allowed_actions'])

    def test_terminal_record_does_not_claim_parent_requires_rereview(self):
        record = self.reviewed_workspace()
        merged = self.op('merge', record)
        self.assertEqual(['WORKSPACE_NOT_READY'], merged['merge_blocked_by'])
        record = self.reviewed_workspace()
        self.op('discard', record)
        w.git(self.root, 'commit', '--allow-empty', '-m', 'later parent commit')
        inspected = self.op('inspect', record)
        self.assertEqual(['WORKSPACE_NOT_READY'], inspected['merge_blocked_by'])
        self.assertEqual(['inspect'], inspected['allowed_actions'])

    def test_corrupt_record_state_is_stably_rejected_without_parent_mutation(self):
        record = self.op('prepare')
        saved = w.load(self.root, record['id'])
        before = (w.git(self.root, 'rev-parse', 'HEAD'), w.git(self.root, 'status', '--porcelain'),
                  (self.root / 'main.txt').read_text())
        for value in (None, '', 'unknown-state', 1, True, [], {}):
            with self.subTest(value=value):
                corrupt = dict(saved)
                if value is None:
                    corrupt.pop('state')
                else:
                    corrupt['state'] = value
                w.save(self.root, corrupt)
                with self.assertRaisesRegex(ValueError, '^INVALID_WORKSPACE_RECORD$'):
                    self.op('inspect', record)
                listed = self.op('list', workspace_ids=[record['id']])
                self.assertEqual([], listed['workspaces'])
                self.assertEqual([record['id']], listed['unavailable_workspace_ids'])
                self.assertEqual(before, (w.git(self.root, 'rev-parse', 'HEAD'),
                    w.git(self.root, 'status', '--porcelain'), (self.root / 'main.txt').read_text()))
        w.save(self.root, saved)
        self.op('fail', record)
        self.op('discard', record)

    def test_expired_task_is_recoverable_without_deleting_changes(self):
        record = self.op('prepare')
        saved = w.load(self.root, record['id'])
        saved['lease_until'] = 0
        w.save(self.root, saved)
        self.assertEqual('failed', self.op('inspect', record)['state'])
        self.assertTrue(Path(record['path']).exists())
        self.op('discard', record)

    def test_empty_implementation_fails_and_preserves_parent_and_workspace(self):
        record = self.op('prepare')
        base = w.git(self.root, 'rev-parse', 'HEAD')
        refusal = self.refusal('seal', record)
        self.assertEqual('NO_IMPLEMENTATION_CHANGES', str(refusal))
        self.assertEqual(0, refusal.details['artifact_evidence']['changed_file_count'])
        view = self.op('inspect', record)
        self.assertEqual('failed', view['state'])
        self.assertEqual('no_changes', view['delivery_state'])
        self.assertFalse(view['acceptance_verified'])
        self.assertFalse(view['merge_ready'])
        self.assertTrue(Path(record['path']).is_dir())
        self.assertEqual(base, w.git(self.root, 'rev-parse', 'HEAD'))
        self.assertEqual('original', (self.root / 'main.txt').read_text())
        self.assertEqual(['inspect', 'discard'], view['allowed_actions'])

    def test_edit_then_restore_or_new_file_then_delete_has_no_artifact(self):
        for mode in ('restore', 'delete'):
            with self.subTest(mode=mode):
                record = self.op('prepare')
                if mode == 'restore':
                    self.op('write', record, path='main.txt', content='changed')
                    self.op('write', record, path='main.txt', content='original')
                else:
                    self.op('write', record, path='new.txt', content='changed')
                    self.op('delete', record, path='new.txt')
                self.assertEqual('NO_IMPLEMENTATION_CHANGES', str(self.refusal('seal', record)))
                self.op('discard', record)

    def test_empty_commits_and_committed_reverts_do_not_count_as_delivery(self):
        for mode in ('empty_commit', 'revert'):
            with self.subTest(mode=mode):
                record = self.op('prepare'); tree = Path(record['path'])
                if mode == 'revert':
                    (tree / 'main.txt').write_text('intermediate')
                    w.git(tree, 'add', '.'); w.git(tree, 'commit', '-m', 'intermediate')
                    (tree / 'main.txt').write_text('original')
                    w.git(tree, 'add', '.'); w.git(tree, 'commit', '-m', 'undo')
                else:
                    w.git(tree, 'commit', '--allow-empty', '-m', 'no-op')
                self.assertNotEqual(record['base'], w.git(tree, 'rev-parse', 'HEAD'))
                self.assertEqual('NO_IMPLEMENTATION_CHANGES', str(self.refusal('seal', record)))
                self.op('discard', record)

    def test_runtime_metadata_alone_does_not_count_as_code_changes(self):
        record = self.op('prepare'); tree = Path(record['path'])
        (tree / '.agent').mkdir(); (tree / '.agent' / 'claim.txt').write_text('complete')
        self.assertEqual('NO_IMPLEMENTATION_CHANGES', str(self.refusal('seal', record)))
        self.assertEqual(record['base'], w.git(tree, 'rev-parse', 'HEAD'))

    def test_real_committed_artifact_and_deletion_have_verifiable_receipts(self):
        for mode in ('edit', 'delete'):
            with self.subTest(mode=mode):
                record = self.op('prepare')
                if mode == 'edit': self.op('write', record, path='main.txt', content='original\n')
                else: self.op('delete', record, path='main.txt')
                view = self.op('seal', record); evidence = view['artifact_evidence']
                self.assertEqual('artifact_ready_pending_review', view['delivery_state'])
                self.assertEqual('ready', view['state'])
                self.assertFalse(view['acceptance_verified'])
                self.assertEqual(record['id'], evidence['workspace_id'])
                self.assertNotEqual(evidence['base_commit'], evidence['artifact_commit'])
                self.assertEqual(1, evidence['changed_file_count'])
                self.assertEqual(['main.txt'], evidence['changed_files'])
                self.assertTrue(all(evidence[k] for k in ('net_diff_verified', 'base_is_ancestor', 'clean_worktree', 'head_matches_commit')))
                self.assertEqual(evidence, self.op('inspect', record)['artifact_evidence'])
                self.assertEqual(record['base'], w.git(self.root, 'rev-parse', 'HEAD'))
                self.op('discard', record)

    def test_legacy_reviewed_empty_artifact_cannot_merge_or_begin_review(self):
        for empty_commit in (False, True):
            record = self.op('prepare'); tree = Path(record['path'])
            if empty_commit: w.git(tree, 'commit', '--allow-empty', '-m', 'legacy empty')
            saved = w.load(self.root, record['id'])
            saved.update(state='ready', reviewed=True, commit=w.git(tree, 'rev-parse', 'HEAD'))
            w.save(self.root, saved)
            self.assert_merge_refused_without_parent_mutation(record, 'NO_IMPLEMENTATION_CHANGES')
            self.assertEqual('NO_IMPLEMENTATION_CHANGES', str(self.refusal('begin_review', record)))
            self.op('discard', record)

    def test_artifact_paths_are_bounded_without_losing_total_count(self):
        record = self.op('prepare'); tree = Path(record['path'])
        for i in range(300):
            (tree / (f'{i:04d}-' + '文' * 60 + '.txt')).write_text('x')
        view = self.op('seal', record); evidence = view['artifact_evidence']
        self.assertEqual(300, evidence['changed_file_count'])
        self.assertLessEqual(len(evidence['changed_files']), 20)
        self.assertTrue(evidence['changed_files_truncated'])
        self.assertTrue(json.loads(w.response_line(view))['ok'])
        self.assertLessEqual(w.units(w.response_line(view)), w.STDOUT_LIMIT)

    def test_artifact_names_preserve_newlines_spaces_and_unicode(self):
        record = self.op('prepare'); name = '  空格\nfile.txt'
        (Path(record['path']) / name).write_text('x')
        view = self.op('seal', record)
        self.assertEqual([name], view['artifact_evidence']['changed_files'])

    def test_inspect_does_not_trust_persisted_artifact_claims(self):
        record = self.op('prepare')
        self.op('write', record, path='main.txt', content='changed')
        self.op('seal', record)
        saved = w.load(self.root, record['id'])
        saved['artifact_evidence'] = {'source': 'forged', 'changed_file_count': 999}
        w.save(self.root, saved)
        evidence = self.op('inspect', record)['artifact_evidence']
        self.assertEqual('runtime_git', evidence['source'])
        self.assertEqual(1, evidence['changed_file_count'])
        (Path(record['path']) / 'main.txt').write_text('uncommitted')
        view = self.op('inspect', record)
        self.assertFalse(view['artifact_evidence']['clean_worktree'])
        self.assertNotEqual('artifact_ready_pending_review', view['delivery_state'])

    def test_renew_keeps_workspace_and_cannot_revive_finished_lease(self):
        record = self.op('prepare')
        self.op('write', record, path='main.txt', content='preserved')
        saved = w.load(self.root, record['id'])
        before = saved['lease_until']
        self.op('renew', record)
        self.assertGreaterEqual(w.load(self.root, record['id'])['lease_until'], before)
        self.assertEqual('preserved', self.op('read', record, path='main.txt')['content'])
        self.op('seal', record)
        with self.assertRaisesRegex(ValueError, 'WORKSPACE_LEASE_LOST'):
            self.op('renew', record)

    def test_pagination_and_utf8_budget(self):
        record = self.op('prepare')
        self.op('write', record, path='text.txt', content='中文' * 30)
        result = self.op('read', record, path='text.txt', offset=2, limit=5)
        self.assertEqual(7, result['next_offset'])
        self.assertEqual(5, len(result['content']))
        with self.assertRaisesRegex(ValueError, 'FILE_TOO_LARGE'):
            self.op('write', record, path='large.txt', content='中' * 65536)

    def test_write_budget_does_not_modify_destination(self):
        record = self.op('prepare')
        saved = w.load(self.root, record['id'])
        saved['written_bytes'] = 16 * 1024 * 1024
        w.save(self.root, saved)
        with self.assertRaisesRegex(ValueError, 'WORKSPACE_WRITE_BUDGET'):
            self.op('write', record, path='main.txt', content='too much')
        self.assertEqual('original', self.op('read', record, path='main.txt')['content'])

    def test_project_validation_rejects_workspace_root_and_nested(self):
        for path in ['/workspace', '/workspace/Eta/nested', '/tmp/project', 'relative']:
            with self.assertRaises(ValueError):
                w.project_path(path)

    def test_authorized_list_ignores_more_than_fifty_unrelated_records(self):
        for index in range(60):
            self.put_record(f'{index + 1:032x}')
        authorized = self.put_record('f' * 32)
        result = self.op('list', workspace_ids=[authorized['id']])
        self.assertEqual([authorized], result['workspaces'])
        self.assertEqual(1, result['total_count'])
        self.assertFalse(result['truncated'])
        self.assertEqual([], result['unavailable_workspace_ids'])
        self.assertEqual(0, result['unavailable_count'])

    def test_authorized_list_empty_and_invalid_ids(self):
        result = self.op('list', workspace_ids=[])
        self.assertEqual([], result['workspaces'])
        self.assertEqual(0, result['total_count'])
        self.assertFalse(result['truncated'])
        self.assertEqual([], result['unavailable_workspace_ids'])
        self.assertEqual(0, result['unavailable_count'])
        with self.assertRaisesRegex(ValueError, 'INVALID_WORKSPACE_ID'):
            self.op('list', workspace_ids=['A' * 32])
        with self.assertRaisesRegex(ValueError, 'INVALID_WORKSPACE_IDS'):
            self.op('list', workspace_ids=tuple())

    def test_unrelated_bad_json_does_not_block_authorized_list(self):
        authorized = self.put_record('a' * 32)
        results = self.root / '.agent' / 'results'
        (results / ('b' * 32 + '.json')).write_text('{not json')
        (results / ('c' * 32 + '.json')).write_text('{also not json')
        result = self.op('list', workspace_ids=[authorized['id']])
        self.assertEqual([authorized], result['workspaces'])
        self.assertEqual(1, result['total_count'])
        self.assertEqual([], result['unavailable_workspace_ids'])
        self.assertEqual(0, result['unavailable_count'])

    def test_bad_matching_entries_are_skipped_without_expanding_authority(self):
        authorized = self.put_record('1' * 32)
        missing = '2' * 32
        corrupt = '3' * 32
        redirected = '4' * 32
        results = self.root / '.agent' / 'results'
        (results / (corrupt + '.json')).write_text('{not json')
        (results / (redirected + '.json')).symlink_to(results / (authorized['id'] + '.json'))
        result = self.op('list', workspace_ids=[authorized['id'], missing, corrupt, redirected])
        self.assertEqual([authorized], result['workspaces'])
        self.assertEqual(1, result['total_count'])
        self.assertFalse(result['truncated'])
        self.assertEqual([missing, corrupt, redirected], result['unavailable_workspace_ids'])
        self.assertEqual(3, result['unavailable_count'])

    def test_load_and_list_reject_record_id_forgery(self):
        requested = '5' * 32
        forged = '6' * 32
        results = self.root / '.agent' / 'results'
        results.mkdir(parents=True, exist_ok=True)
        (results / (requested + '.json')).write_text('{"id": "' + forged + '", "state": "editing"}')
        with self.assertRaisesRegex(ValueError, 'INVALID_WORKSPACE_RECORD'):
            w.load(self.root, requested)
        result = self.op('list', workspace_ids=[requested])
        self.assertEqual([], result['workspaces'])
        self.assertEqual(0, result['total_count'])
        self.assertEqual([requested], result['unavailable_workspace_ids'])
        self.assertEqual(1, result['unavailable_count'])

    def test_authorized_lists_for_different_owners_do_not_leak(self):
        first = self.put_record('7' * 32)
        second = self.put_record('8' * 32)
        first_result = self.op('list', workspace_ids=[first['id']])
        second_result = self.op('list', workspace_ids=[second['id']])
        self.assertEqual([first], first_result['workspaces'])
        self.assertEqual([second], second_result['workspaces'])
        self.assertEqual([], first_result['unavailable_workspace_ids'])
        self.assertEqual([], second_result['unavailable_workspace_ids'])

    def test_authorized_list_reports_truncation(self):
        records = [self.put_record(f'{1000 + index:032x}') for index in range(55)]
        result = self.op('list', workspace_ids=[record['id'] for record in records])
        self.assertEqual(55, result['total_count'])
        self.assertTrue(result['truncated'])
        self.assertEqual(50, len(result['workspaces']))
        self.assertEqual([record['id'] for record in records[:50]],
                         [record['id'] for record in result['workspaces']])
        self.assertEqual([], result['unavailable_workspace_ids'])
        self.assertEqual(0, result['unavailable_count'])


    def big_file(self, record, name='Big.kt', newline='\n', final=True):
        rows = [f'line {i:05d} ' + 'x' * 40 for i in range(1, 6001)]
        rows[4199] = 'val unique = "needle"'
        text = newline.join(rows) + (newline if final else '')
        path = Path(record['path']) / name
        path.write_bytes(text.encode())
        return path, text

    def test_large_file_line_paging_reaches_end_and_reassembles(self):
        record = self.op('prepare')
        path, text = self.big_file(record)
        self.assertGreater(len(text.encode()), 300_000)
        parts, start = [], 1
        while start is not None:
            page = self.op('read', record, path='Big.kt', start_line=start, line_count=2000)
            self.assertLessEqual(len(page['content']), w.OUTPUT_BUDGET)
            self.assertEqual(6000, page['total_lines'])
            parts.append(page['content'])
            start = page['next_line']
        self.assertEqual(text, ''.join(parts))
        chars = self.op('read', record, path='Big.kt', offset=len(text) - 10, limit=4000)
        self.assertEqual(text[-10:], chars['content'])
        self.assertIsNone(chars['next_offset'])

    def test_search_reports_line_numbers_and_context(self):
        record = self.op('prepare')
        self.big_file(record)
        found = self.op('search', record, path='Big.kt', query='"needle"')
        self.assertEqual([4200], [m['line'] for m in found['matches']])
        self.assertTrue(found['matches'][0]['before'][0].startswith('line 04199'))
        everywhere = self.op('search', record, query='needle')
        self.assertEqual(['Big.kt'], [m['path'] for m in everywhere['matches']])
        many = self.op('search', record, path='Big.kt', query=r'line \d+', regex=True, max_results=5)
        self.assertEqual(5, len(many['matches']))
        self.assertTrue(many['truncated'])
        with self.assertRaisesRegex(ValueError, 'INVALID_REGEX'):
            self.op('search', record, query='(', regex=True)

    def test_replace_unique_match_keeps_every_other_byte(self):
        record = self.op('prepare')
        for name, newline, final in [('Lf.kt', '\n', True), ('Crlf.kt', '\r\n', True), ('NoEnd.kt', '\n', False)]:
            path, text = self.big_file(record, name, newline, final)
            os.chmod(path, 0o755)
            old = 'line 04199 ' + 'x' * 40 + '\nval unique = "needle"'
            result = self.op('replace', record, path=name, old_text=old, new_text=old.replace('needle', 'pin'))
            self.assertEqual(1, result['replacements'])
            self.assertEqual(newline == '\r\n', result['line_endings_adapted'])
            self.assertEqual(text.replace('"needle"', '"pin"').encode(), path.read_bytes())
            self.assertEqual(0o755, path.stat().st_mode & 0o777)
        leftovers = [p.name for p in Path(record['path']).iterdir() if p.name.endswith('.tmp')]
        self.assertEqual([], leftovers)

    def test_replace_rejects_ambiguous_missing_and_oversized_patches(self):
        record = self.op('prepare')
        path, text = self.big_file(record)
        for kwargs, code in [
            ({'old_text': 'x' * 40, 'new_text': 'y'}, 'MATCH_COUNT_MISMATCH'),
            ({'old_text': 'absent text', 'new_text': 'y'}, 'OLD_TEXT_NOT_FOUND'),
            ({'old_text': 'needle', 'new_text': 'y' * w.PATCH_LIMIT}, 'PATCH_TOO_LARGE'),
            ({'old_text': '', 'new_text': 'y'}, 'INVALID_REPLACEMENT'),
        ]:
            with self.assertRaisesRegex(ValueError, code):
                self.op('replace', record, path='Big.kt', **kwargs)
        self.assertEqual(text.encode(), path.read_bytes())
        with self.assertRaisesRegex(ValueError, 'INVALID_ARGUMENT'):
            self.op('replace', record, path='Big.kt', old_text='x' * 40, new_text='z', expected_count=5999)
        self.assertEqual(text.encode(), path.read_bytes())
        result = self.op('replace', record, path='Big.kt', old_text='line 0001', new_text='LINE 0001', expected_count=10)
        self.assertEqual(10, result['replacements'])
        self.assertEqual(text.replace('line 0001', 'LINE 0001').encode(), path.read_bytes())

    def test_replace_and_search_obey_path_and_size_limits(self):
        record = self.op('prepare')
        tree = Path(record['path'])
        for path in ['../outside', '.git/config', '.agent/x']:
            with self.assertRaises(ValueError):
                self.op('replace', record, path=path, old_text='a', new_text='b')
        (tree / 'escape').symlink_to(self.root)
        with self.assertRaisesRegex(ValueError, 'SYMLINK'):
            self.op('replace', record, path='escape/main.txt', old_text='original', new_text='bad')
        with self.assertRaisesRegex(ValueError, 'SYMLINK'):
            self.op('search', record, path='escape', query='original')
        self.assertEqual('original', (self.root / 'main.txt').read_text())
        huge = tree / 'huge.txt'
        with open(huge, 'wb') as out:
            out.truncate(w.FILE_LIMIT + 1)
        for action, kwargs in [('read', {}), ('replace', {'old_text': 'a', 'new_text': 'b'})]:
            with self.assertRaisesRegex(ValueError, 'FILE_TOO_LARGE'):
                self.op(action, record, path='huge.txt', **kwargs)

    def test_whole_file_write_cannot_clobber_large_file(self):
        record = self.op('prepare')
        path, text = self.big_file(record)
        with self.assertRaisesRegex(ValueError, 'USE_REPLACE_FOR_LARGE_FILE'):
            self.op('write', record, path='Big.kt', content='short')
        self.assertEqual(text.encode(), path.read_bytes())

    def test_frozen_workspace_rejects_replace(self):
        record = self.op('prepare')
        self.op('write', record, path='main.txt', content='original changed')
        self.op('seal', record)
        with self.assertRaisesRegex(ValueError, 'WORKSPACE_FROZEN'):
            self.op('replace', record, path='main.txt', old_text='original', new_text='late')

    def test_cli_reports_refusal_details(self):
        record = self.op('prepare')
        self.big_file(record)
        args = {'project': str(self.root), 'action': 'replace', 'workspace_id': record['id'],
                'path': 'Big.kt', 'old_text': 'x' * 40, 'new_text': 'y'}
        try:
            w.locked(self.root, args)
        except w.Refused as error:
            self.assertEqual('MATCH_COUNT_MISMATCH', str(error))
            self.assertEqual(5999, error.details['matches'])
        else:
            self.fail('expected refusal')


    def test_catastrophic_regex_stops_at_the_search_deadline(self):
        record = self.op('prepare')
        (Path(record['path']) / 'r.txt').write_text('a' * 40 + '!\n')
        saved = w.SEARCH_SECONDS
        w.SEARCH_SECONDS = 0.5
        try:
            started = time.monotonic()
            with self.assertRaisesRegex(ValueError, 'SEARCH_TIMEOUT'):
                self.op('search', record, path='r.txt', query='(a+)+$', regex=True)
            self.assertLess(time.monotonic() - started, 5)
        finally:
            w.SEARCH_SECONDS = saved
        # The timer is cleared, so a later ordinary search is not interrupted.
        self.assertEqual(1, len(self.op('search', record, path='r.txt', query='!')['matches']))

    def test_crlf_new_text_is_not_doubled(self):
        record = self.op('prepare')
        path = Path(record['path']) / 'c.txt'
        path.write_bytes(b'a\r\nb\r\nc\r\n')
        for new in ['x\r\ny', 'x\ny']:
            path.write_bytes(b'a\r\nb\r\nc\r\n')
            result = self.op('replace', record, path='c.txt', old_text='a\nb', new_text=new)
            self.assertTrue(result['line_endings_adapted'])
            self.assertEqual(b'x\r\ny\r\nc\r\n', path.read_bytes())

    def test_character_pages_fit_the_bridge_after_json_escaping(self):
        record = self.op('prepare')
        tree = Path(record['path'])
        for name, text in [('ctl.txt', '\x01\u2028' * 4000), ('emoji.txt', '\U0001F600' * 9000)]:
            (tree / name).write_text(text)
            parts, offset = [], 0
            while offset is not None:
                page = self.op('read', record, path=name, offset=offset, limit=4000)
                line = json.dumps({'ok': True, **page}, ensure_ascii=False)
                self.assertLessEqual(w.units(line), w.STDOUT_LIMIT)
                self.assertGreater(len(page['content']), 0)
                parts.append(page['content'])
                offset = page['next_offset']
            self.assertEqual(text, ''.join(parts))

    def test_line_reads_count_utf16_units(self):
        record = self.op('prepare')
        (Path(record['path']) / 'e.txt').write_text(('\U0001F600' * 100 + '\n') * 500)
        start = 1
        while start is not None:
            page = self.op('read', record, path='e.txt', start_line=start, line_count=2000)
            self.assertLessEqual(w.units(json.dumps({'ok': True, **page}, ensure_ascii=False)), w.STDOUT_LIMIT)
            start = page['next_line']

    def test_response_line_never_exceeds_the_bridge(self):
        small = json.loads(w.response_line({'content': 'ok'}))
        self.assertEqual({'ok': True, 'content': 'ok'}, small)
        huge = w.response_line({'content': '\U0001F600' * w.STDOUT_LIMIT})
        self.assertLessEqual(w.units(huge), w.STDOUT_LIMIT)
        self.assertEqual('WORKSPACE_OUTPUT_TOO_LARGE', json.loads(huge)['code'])


if __name__ == '__main__':
    unittest.main()
