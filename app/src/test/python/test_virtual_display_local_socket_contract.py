from pathlib import Path
import re
import unittest

# android.net.LocalSocket implements isClosed/isInputShutdown/isOutputShutdown/
# getRemoteSocketAddress as `throw new UnsupportedOperationException()`. Calling any of them
# made every status/evidence read on a reconnected owner client fail with
# RECOVERY_STATUS_FAILED before the manual close could send its release.
FORBIDDEN = ("isClosed", "isInputShutdown", "isOutputShutdown", "remoteSocketAddress", "getRemoteSocketAddress")


class VirtualDisplayLocalSocketContractTest(unittest.TestCase):
    def test_owner_client_never_calls_unsupported_local_socket_methods(self):
        app = Path(__file__).resolve().parents[3]
        client = (app / "src/main/kotlin/io/github/mangi/eta/agent/device/VirtualDisplayOwnerClient.kt").read_text()
        code = "\n".join(line.split("//", 1)[0] for line in client.splitlines())
        code = re.sub(r"/\*.*?\*/", "", code, flags=re.S)
        for name in FORBIDDEN:
            self.assertNotRegex(code, r"socket\s*\.\s*" + name + r"\b", name)
        self.assertIn("process?.isAlive ?: socket.isConnected", code)


if __name__ == "__main__":
    unittest.main()
