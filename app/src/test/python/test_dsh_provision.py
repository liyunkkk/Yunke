"""Tests for app/src/main/assets/agent/dsh_provision.py."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
PROVISION_SCRIPT = ROOT / 'src/main/assets/agent/dsh_provision.py'


class DshProvisionScriptTest(unittest.TestCase):

    def run_script(self, payload):
        proc = subprocess.run(
            [sys.executable, str(PROVISION_SCRIPT), json.dumps(payload)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        return proc

    def test_status_returns_valid_json_and_does_not_leak_key_content(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            key_file = os.path.join(tmpdir, "key")
            with open(key_file, "w", encoding="utf-8") as f:
                f.write("DEEPSEEK_BASE_URL=https://api.deepseek.com/v1\n")
                f.write("DEEPSEEK_API_KEY=sk-super-secret-leak-test-token\n")

            payload = {
                "action": "status",
                "key_file": key_file,
                "runtime_bin": "/nonexistent/bin/dsh",
            }

            res = self.run_script(payload)
            self.assertEqual(res.returncode, 0)
            lines = [line.strip() for line in res.stdout.strip().splitlines() if line.strip()]
            self.assertEqual(len(lines), 1, "stdout 必须严格输出一行 JSON")

            data = json.loads(lines[0])
            self.assertTrue(data.get("ok"))
            self.assertFalse(data.get("installed"))
            self.assertIsNone(data.get("version"))
            self.assertTrue(data.get("key_exists"))
            self.assertEqual(data.get("key_file"), key_file)

            # 严格安全断言：绝不回显密钥内容
            self.assertNotIn("sk-super-secret-leak-test-token", res.stdout)
            self.assertNotIn("sk-super-secret-leak-test-token", res.stderr)

    def test_unknown_action_returns_structured_error(self):
        payload = {"action": "invalid_unknown_action"}
        res = self.run_script(payload)
        self.assertEqual(res.returncode, 0)
        lines = [line.strip() for line in res.stdout.strip().splitlines() if line.strip()]
        self.assertEqual(len(lines), 1)

        data = json.loads(lines[0])
        self.assertFalse(data.get("ok"))
        self.assertEqual(data.get("code"), "UNKNOWN_ACTION")

    def test_missing_argument_returns_structured_error(self):
        proc = subprocess.run(
            [sys.executable, str(PROVISION_SCRIPT)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc.returncode, 0)
        lines = [line.strip() for line in proc.stdout.strip().splitlines() if line.strip()]
        self.assertEqual(len(lines), 1)

        data = json.loads(lines[0])
        self.assertFalse(data.get("ok"))
        self.assertEqual(data.get("code"), "INVALID_ARGUMENTS")

    def test_pinned_version_constant(self):
        # 验证固定版本为 0.1.5rc1
        content = PROVISION_SCRIPT.read_text(encoding='utf-8')
        self.assertIn('PINNED_VERSION = "0.1.5rc1"', content)
        self.assertIn('TARGET_DIR = "/opt/yunke-dsh/runtime"', content)
        self.assertIn('DEFAULT_SYMLINK = "/usr/local/bin/dsh"', content)
        self.assertIn('DEFAULT_KEY_FILE = "/root/.dsh/key"', content)


if __name__ == '__main__':
    unittest.main()
