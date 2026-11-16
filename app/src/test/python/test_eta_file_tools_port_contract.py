"""Non-Gradle port checks; JVM behavior suites run in cloud CI."""
from pathlib import Path
import hashlib
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
MAIN = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/agent'


def quote(value):
    return "'" + str(value).replace("'", "'\\''") + "'"


class EtaFileToolPortContract(unittest.TestCase):
    def text(self, path):
        return (MAIN / path).read_text()

    def test_all_seven_tools_have_dispatch_and_schema(self):
        dispatcher = self.text('terminal/FileToolDispatcher.kt')
        catalog = self.text('model/AgentFileToolCatalog.kt')
        for name in ('read_file', 'write_file', 'list_directory', 'edit_file',
                     'stat_file', 'glob_files', 'grep_files'):
            self.assertIn('"' + name + '" ->', dispatcher)
            self.assertIn('tool("' + name + '"', catalog)
        terminal = self.text('model/AgentTerminalToolCatalog.kt')
        self.assertEqual(terminal.count('AgentFileToolCatalog.appendTo(tools)'), 1)
        self.assertNotIn('name = "read_file"', terminal)

    def test_controller_reuses_runtime_instead_of_host_mapping_for_explicit_linux(self):
        source = self.text('terminal/RootShellTerminalController.kt')
        body = source.split('fun executeFileTool(', 1)[1].split('private fun normalizeIdentity', 1)[0]
        for fragment in ('normalizeEnvironment', 'defaultIdentity(environment)',
                         'environmentPreflight', 'LocalFileToolBackend',
                         'ShellFileToolBackend', 'rootfsPathFor(environment)',
                         'linuxRootfsPath = rootfs', 'linuxSharedMounts = sharedMounts',
                         'sharedMountsFor(environment)', 'maxOutputBytes = maxOutputBytes'):
            self.assertIn(fragment, body)
        self.assertIn('!args.has("environment")', body)
        for legacy in ('fun readFile(', 'fun writeFile(', 'fun listDirectory('):
            self.assertIn(legacy, body)

    def test_version_and_utf8_continuation_guards_present(self):
        operations = self.text('terminal/AgentFileOperations.kt')
        for fragment in ('expectedSha256 = sha256', 'expectedRevision = stat.revision',
                         'AMBIGUOUS_MATCH', 'MATCH_NOT_FOUND', 'next_offset_bytes'):
            self.assertIn(fragment, operations)
        reader = self.text('terminal/FileToolTextReader.kt')
        self.assertIn('CodingErrorAction.REPORT', reader)
        self.assertIn('text.toByteArray(Charsets.UTF_8).size', reader)
        supervisor = self.text('terminal/ShellProcessSupervisor.kt')
        self.assertIn('maxOutputBytes: Int = ONE_SHOT_OUTPUT_MAX_BYTES', supervisor)
        self.assertIn('output.isTruncated() || outputThread.isAlive', supervisor)
        self.assertIn('SHELL_STDIN_FAILED_EXIT_CODE', supervisor)

    def test_real_shell_handles_literal_names_and_block_read_protocol(self):
        with tempfile.TemporaryDirectory(prefix='eta-file-contract-') as directory:
            path = Path(directory) / "-中文 '\n$(printf injected)`echo bad`"
            data = bytes(index % 251 for index in range(20000))
            path.write_bytes(data)
            result = subprocess.run(['sh', '-c', f'dd if={quote(path)} bs=4096 skip=1 count=2'],
                                    capture_output=True, check=True)
            self.assertEqual(result.stdout[1:7001], data[4097:11097])
            stat = subprocess.run(['sh', '-c', f"LC_ALL=C stat -c {quote('%s'+chr(10)+'%d:%i:%s:%Y:%Z:%y:%z')} -- {quote(path)}"],
                                  capture_output=True, check=True).stdout.decode()
            fields = stat.split('\n', 1)
            self.assertEqual(int(fields[0]), len(data))
            self.assertGreaterEqual(len(fields[1].split(':', 5)), 6)
            checksum = subprocess.run(['sh', '-c', f'sha256sum < {quote(path)}'],
                                      capture_output=True, check=True).stdout.decode().split()[0]
            self.assertEqual(checksum, hashlib.sha256(data).hexdigest())
            listing = subprocess.run(['sh', '-c', f'find {quote(directory)} -mindepth 1 -maxdepth 1 -print0'],
                                     capture_output=True, check=True).stdout
            self.assertEqual(listing, str(path).encode() + b'\0')


if __name__ == '__main__':
    unittest.main()
