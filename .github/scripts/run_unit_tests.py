"""CI-only test runner with bounded thread snapshots; never masks Gradle's exit code."""
from pathlib import Path
import subprocess
import threading

output = Path("app/build/reports/test-diagnostics")
output.mkdir(parents=True, exist_ok=True)
finished = threading.Event()


def snapshots():
    for sequence in range(1, 5):
        if finished.wait(120):
            return
        try:
            processes = subprocess.run(["jcmd", "-l"], capture_output=True, text=True,
                                       timeout=10, check=False)
            selected = [line.split()[0] for line in processes.stdout.splitlines()
                        if ("GradleWorkerMain" in line or "GradleDaemon" in line)
                        and line.split() and line.split()[0].isdigit()][:4]
            for pid in selected:
                path = output / f"threads-{sequence}-{pid}.txt"
                with path.open("w") as stream:
                    subprocess.run(["jcmd", pid, "Thread.print", "-l"], stdout=stream,
                                   stderr=subprocess.STDOUT, timeout=15, check=False)
                print(f"CI diagnostic: captured {path.name}", flush=True)
        except Exception as error:
            # No exception text (potentially includes command/configuration details).
            print(f"CI diagnostic unavailable: {type(error).__name__}", flush=True)


watcher = threading.Thread(target=snapshots, name="ci-test-diagnostics", daemon=True)
watcher.start()
try:
    result = subprocess.run(["./gradlew", "--no-daemon", "--no-configuration-cache",
                             "--console=plain", ":app:testDebugUnitTest"], check=False)
finally:
    finished.set()
    watcher.join(timeout=70)
raise SystemExit(result.returncode)
