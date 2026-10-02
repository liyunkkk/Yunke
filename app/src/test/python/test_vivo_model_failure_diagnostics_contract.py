"""Local source safety contracts; Kotlin tests exercise classification and worker behavior."""
from pathlib import Path
import re
import unittest

MAIN = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'
VIVO = MAIN / 'agent/vivo'


class VivoModelFailureDiagnosticsContractTest(unittest.TestCase):
    def test_classifier_is_bounded_typed_and_never_uses_sensitive_text(self):
        source = (VIVO / 'VivoModelFailureClassifier.kt').read_text()
        self.assertIn('const val MAX_CAUSES = 8', source)
        self.assertIn('seen.size < MAX_CAUSES', source)
        self.assertIn('seen.any { it === item }', source)
        self.assertIn('current = try { item.cause } catch (_: Throwable) { null }', source)
        self.assertIn('Regex("HTTP_[1-5][0-9]{2}")', source)
        typed = source.split('is AgentModelFailure -> {', 1)[1].split('is AgentOutputLimitException', 1)[0]
        self.assertIn('httpCode.matches(item.code)', typed)
        self.assertIn('item.code.substring(5).toInt()', typed)
        self.assertIn('ModelCode.entries.firstOrNull { it.name == item.code }', typed)
        self.assertEqual(3, source.count('item.code'))
        for forbidden in ('.message', '.localizedMessage', '.diagnostic', '.stackTrace',
                          '.javaClass', 'qualifiedName', 'simpleName', '.toString(',
                          'Log.', 'println(', 'printStackTrace', 'System.nanoTime',
                          'currentTimeMillis', 'suppressed', 'getDeclaredField', '.headers', '.body'):
            self.assertNotIn(forbidden, source)
        # A generic require/check exception proves neither configuration nor HTTP failure.
        self.assertNotIn('is IllegalArgumentException', source)
        self.assertNotIn('is IllegalStateException', source)
        for category in ('HTTP_400', 'HTTP_401', 'HTTP_403', 'HTTP_404', 'HTTP_408',
                         'HTTP_413', 'HTTP_429', 'HTTP_5XX', 'HTTP_OTHER', 'DNS', 'TLS',
                         'CONNECT', 'IO', 'PARSE', 'CANCEL', 'CONFIG', 'UNKNOWN',
                         'OUTPUT_LIMIT', 'EMPTY_RESPONSE'):
            self.assertRegex(source, r'\b' + category + r'\b')

    def test_worker_keeps_single_request_fixed_results_and_fatal_propagation(self):
        source = (VIVO / 'VivoTextBridgeService.kt').read_text()
        self.assertEqual(1, source.count('ModelFeatureCompletion.complete('))
        self.assertEqual(1, source.count('VivoTextModelGateway.selectedConfig()'))
        self.assertIn('timeoutMs = VivoTextBridgePolicy.TIMEOUT_MS', source)
        self.assertIn('outputLimit = 2048', source)
        worker = source.split('internal fun executeModelCall(', 1)[1]
        self.assertEqual(1, worker.count('val config = selectConfig()'))
        self.assertEqual(1, worker.count('val text = complete(config, messages)'))
        self.assertLess(worker.index('Stage.MODEL_STARTED'), worker.index('val text = complete('))
        self.assertLess(worker.index('val text = complete('), worker.index('Stage.MODEL_FINISHED'))
        self.assertIn('catch (error: Throwable)', worker)
        self.assertIn('if (modelStarted) VivoBridgeDiagnostics.Stage.MODEL_FAILED', worker)
        self.assertIn('else VivoBridgeDiagnostics.Stage.SERVICE_PREPARATION_FAILED', worker)
        self.assertIn('code = if (error is Exception && controller.isCancelled) "CANCELLED" else "MODEL_ERROR"', worker)
        self.assertIn('if (error !is Exception) throw error', worker)
        self.assertIn('finally {\n                deliver(code, answer)', worker)
        for forbidden in ('.message', '.localizedMessage', '.diagnostic', '.stackTrace',
                          '.javaClass', '.toString(', 'Log.', 'printStackTrace(', 'retry(',
                          'AgentModelRetry', 'AgentRuntimeService', 'fallback'):
            self.assertNotIn(forbidden, source)
        self.assertIn('if (text.length > VivoTextBridgePolicy.MAX_RESULT) code = "RESULT_TOO_LARGE"', source)
        self.assertIn('if (config == null)', worker)
        self.assertIn('code = "NO_MODEL"', worker)

    def test_completion_observer_is_opt_in_closed_and_failure_is_rethrown_unchanged(self):
        source = (MAIN / 'agent/model/ModelFeatureCompletion.kt').read_text()
        self.assertIn('onFailurePhase: ((FailurePhase) -> Unit)? = null', source)
        self.assertIn('if (onFailurePhase != null) runCatching { onFailurePhase(phase) }', source)
        self.assertIn('throw error\n        } finally {', source)
        self.assertEqual(1, source.count('onFailurePhase(phase)'))
        for phase in ('CONFIG_VALIDATION', 'CANCELLATION_CHECK', 'REQUEST_PREPARATION',
                      'PROVIDER_CALL', 'STOP_REASON_VALIDATION', 'TOOL_CALL_VALIDATION', 'BODY_VALIDATION'):
            self.assertIn('phase = FailurePhase.' + phase, source)
        self.assertEqual(1, source.count('ProviderClientFactory.getClient(requestConfig)).complete('))
        self.assertIn('ProviderRequest(requestConfig, messages, JSONArray(), sessionId, usageConversationId), child,', source)
        self.assertIn('require(response.stopReason == AssistantStopReason.END_TURN)', source)
        self.assertIn('require((response.assistantMessage.optJSONArray("tool_calls")?.length() ?: 0) == 0)', source)
        self.assertIn('require(it.isNotBlank() && it != "null")', source)
        self.assertIn('watchdog.interrupt()\n            child.cancel()\n            binding.close()', source)
        for forbidden in ('Log.', '.message', '.diagnostic', '.stackTrace', '.javaClass', '.toString('):
            self.assertNotIn(forbidden, source)
        service = (VIVO / 'VivoTextBridgeService.kt').read_text()
        self.assertIn('onFailurePhase = onFailurePhase', service)
        for phase in ('STOP_REASON_VALIDATION', 'TOOL_CALL_VALIDATION', 'BODY_VALIDATION'):
            self.assertIn('ModelFeatureCompletion.FailurePhase.' + phase + ' -> VivoBridgeDiagnostics.FailurePhase.' + phase, service)

    def test_terminal_logging_is_after_single_terminal_claim_and_wire_has_no_diagnostics(self):
        source = (VIVO / 'VivoTextBridgeService.kt').read_text()
        finish = source.split('private fun finish(', 1)[1].split('private fun send(', 1)[0]
        self.assertLess(finish.index('compareAndSet(false, true)'), finish.index('Stage.SERVICE_TERMINAL'))
        self.assertIn('terminalCode = VivoBridgeDiagnostics.TerminalCode.fromWire(code)', finish)
        self.assertIn('if (notify && !destroyed) send(', finish)
        send = source.split('private fun send(', 1)[1].split('override fun onDestroy()', 1)[0]
        self.assertEqual(['request_id', 'code', 'text'], re.findall(r'putString\("([a-z_]+)"', send))
        self.assertIn('if (code == "OK" && text != null)', send)
        stop = source.split('private fun stop(', 1)[1].split('private fun finish(', 1)[0]
        self.assertIn('call.controller.cancel()', stop)
        self.assertIn('call.thread.interrupt()', stop)
        self.assertNotIn('ledger.release(', stop)
        self.assertIn('main.post { if (active === revoked) stop(revoked, "CANCELLED") }', source)

    def test_log_vocabulary_and_budget_remain_closed_and_shared(self):
        source = (VIVO / 'VivoBridgeDiagnostics.kt').read_text()
        self.assertIn('totalLimit = 80, perStageLimit = 4', source)
        self.assertEqual(1, source.count('VivoDiagnosticBudget('))
        self.assertEqual(1, source.count('budget.claim('))
        self.assertEqual(['i'], re.findall(r'Log\.([a-z]+)\(', source))
        self.assertIn('fun fromWire(code: String): TerminalCode = entries.firstOrNull { it.name == code } ?: UNKNOWN', source)
        terminal = source.split('enum class TerminalCode {', 1)[1].split(';', 1)[0]
        self.assertEqual(['OK', 'NO_MODEL', 'RESULT_TOO_LARGE', 'CANCELLED', 'MODEL_ERROR',
                          'UNAVAILABLE', 'TIMEOUT', 'CALLER_GONE', 'SERVICE_STOPPED', 'UNKNOWN'],
                         re.findall(r'\b[A-Z_]+\b', terminal))
        self.assertIn('NATIVE_REPLY_ENQUEUED, NATIVE_SINK_FAILED,\n        SERVICE_PREPARATION_FAILED, MODEL_FAILED,', source)
        for forbidden in ('.message', '.localizedMessage', '.diagnostic', '.stackTrace', '.cause',
                          '.javaClass', '.toString(', 'printStackTrace', 'Log.e(', 'Log.w('):
            self.assertNotIn(forbidden, source)


if __name__ == '__main__':
    unittest.main()
