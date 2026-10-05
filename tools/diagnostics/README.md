# Detailed Compose jank capture

This adds **diagnostics only**. No rendering, scrolling, expansion, footer, or voice settings change.

Enable Eta's existing file-logging switch and capture system tracing for `io.github.mangi.eta`.
`ComposeSystemTrace` is installed once at application startup. Compiler-generated labels are
bridged to Android trace sections prefixed `Eta.compose:` on the main thread only. They describe
function execution (initial composition or recomposition), not necessarily a committed UI change.
No argument values or message content are recorded. Names are capped at 127 UTF-16 characters.
The bridge does not provide arbitrary method call stacks or allocation stacks.

The bridge requires both the file-logging switch and an active Android app trace. It does not
write these sections to eta-app.log. An already open outer section is drained when the logging
switch changes to keep nested sections balanced. Do not install a second CompositionTracer.

Run on the Android root backend, through a persistent `terminal daemon_start`, not a run-scoped
async shell (the latter can be reclaimed before trace finalization):

```sh
perfetto --txt -c /data/local/tmp/eta/compose-jank.pbtxt \
  -o /data/misc/perfetto-traces/eta-compose-jank.perfetto-trace
```

Copy this directory's `compose-jank.pbtxt` to the config path before capture. The configuration
streams every second, lasts 60 seconds, and caps output at 512 MiB. Check the daemon log for
`Wrote ... bytes`, file size, trace bounds, and loss stats before claiming a successful capture.
Even streaming can lose data under overload: a nonempty file is not proof of complete coverage.

With a release APK installed and restarted, verify that actual output contains `Eta.compose:`
sections with recognizable function labels nested under `Recomposer:recompose`. Compare running
CPU time and waits rather than interpreting an entire concurrent GC as a UI pause. Trace itself
adds overhead: repeat the same interaction with tracing disabled as a control.

Do not claim device verification from unit tests alone. Function names without source details
can occur for precompiled dependencies; skipped bodies produce no function section.
