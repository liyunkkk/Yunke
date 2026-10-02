"""Source/manifest safety contracts, not a substitute for device verification."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
MAIN = ROOT / 'src/main'
ANDROID = '{http://schemas.android.com/apk/res/android}'


class VivoTextBridgeContractTest(unittest.TestCase):
    def test_component_requires_independent_opt_in_and_is_not_an_agent(self):
        manifest = ET.parse(MAIN / 'AndroidManifest.xml').getroot()
        services = manifest.findall('application/service')
        matches = [s for s in services if s.get(ANDROID + 'name') == '.agent.vivo.VivoTextBridgeService']
        self.assertEqual(1, len(matches))
        service = matches[0]
        self.assertEqual('@bool/vivo_text_bridge_enabled', service.get(ANDROID + 'enabled'))
        self.assertEqual('true', service.get(ANDROID + 'exported'))
        self.assertEqual([], service.findall('intent-filter'))
        values = ET.parse(MAIN / 'res/values/vivo_text_bridge.xml').getroot()
        value = values.find("bool[@name='vivo_text_bridge_enabled']")
        self.assertIsNotNone(value)
        self.assertEqual('true', value.text.strip())
        prefs = (MAIN / 'kotlin/io/github/mangi/eta/config/Prefs.kt').read_text()
        self.assertIn('VIVO_TEXT_BRIDGE to false', prefs)
        service_source = (MAIN / 'kotlin/io/github/mangi/eta/agent/vivo/VivoTextBridgeService.kt').read_text()
        self.assertIn('&& VivoBridgeConsent.localEnabled()', service_source)

    def test_service_has_no_agent_executor_or_sensitive_logging(self):
        source = (MAIN / 'kotlin/io/github/mangi/eta/agent/vivo/VivoTextBridgeService.kt').read_text()
        for forbidden in ('AgentRuntimeService.', 'AgentExecutionService', 'AgentLoop',
                          'Log.', 'println(', 'printStackTrace(', 'FileOutputStream',
                          'setSelectedProviderId(', 'setSelectedModelId('):
            self.assertNotIn(forbidden, source)
        self.assertIn('msg.sendingUid', source)
        self.assertIn('linkToDeath', source)
        self.assertIn('unlinkToDeath', source)
        self.assertIn('compareAndSet(false, true)', source)


    def test_current_signer_pin_is_exact_not_any_historical_signer(self):
        policy = (MAIN / 'kotlin/io/github/mangi/eta/agent/vivo/VivoTextBridgePolicy.kt').read_text()
        self.assertIn('const val SIGNER = "915191fccf5058fa4b21c9c8ea8897040d313d18838850e986fc00055117d1db"', policy)
        self.assertNotIn('bcc35d4d3606f154f0402ab7634e8490c0b244c2675c3c6238986987024f0c02', policy)
        for gate in ('uid in 10000..19999', 'packages == listOf(PACKAGE)',
                     'version == 6090021L', 'signers == listOf(SIGNER)',
                     'enabled && model == "V2419A" && sdk == 35'):
            self.assertIn(gate, policy)
        for path in ('hook/vivo/VivoHooks.kt', 'agent/vivo/VivoTextBridgeService.kt'):
            source = (MAIN / 'kotlin/io/github/mangi/eta' / path).read_text()
            self.assertIn('apkContentsSigners', source)
            self.assertIn('VivoTextBridgePolicy.callerAllowed(', source)
            self.assertNotIn('signingCertificateHistory', source)
            self.assertNotIn('hasSigningCertificate(', source)

    def test_bootstrap_original_call_is_once_outside_failure_capture(self):
        # Source guard: identity and business registration happen before the single
        # vendor onCreate. A thrown original call is diagnosed and rethrown unchanged.
        source = (MAIN / 'kotlin/io/github/mangi/eta/hook/vivo/VivoHooks.kt').read_text()
        callback = source.split('intercept("vivo.bootstrap"', 1)[1].split(
            'internal fun <T> bootstrapCopilotOnCreate', 1)[0]
        proceed = callback.index('chain.proceed()')
        self.assertEqual(1, callback.count('chain.proceed()'))
        self.assertLess(callback.index('applicationContext'), proceed)
        self.assertLess(callback.index('::supported'), proceed)
        self.assertLess(callback.index('bootstrapCopilotOnCreate('), proceed)
        helper = source.split('internal fun <T> bootstrapCopilotOnCreate', 1)[1].split(
            'private fun enabled', 1)[0]
        self.assertIn('BOOTSTRAP_ORIGINAL_THREW', helper)
        self.assertIn('throw error', helper)

    def test_bootstrap_short_circuits_and_complete_registration_are_diagnosable(self):
        source = (MAIN / 'kotlin/io/github/mangi/eta/hook/vivo/VivoHooks.kt').read_text()
        for stage in ('BOOTSTRAP_CONTEXT_UNAVAILABLE', 'BOOTSTRAP_IDENTITY_REJECTED',
                      'BOOTSTRAP_IDENTITY_QUERY_FAILED', 'BOOTSTRAP_ALREADY_CLAIMED',
                      'BOOTSTRAP_INIT_FAILED'):
            self.assertIn('VivoBridgeDiagnostics.Stage.' + stage, source)
        identity = source.split('private fun supported(context:', 1)[1].split('private fun registerBusiness', 1)[0]
        self.assertIn('Boolean? = runCatching', identity)
        self.assertIn('}.getOrNull()', identity)
        self.assertIn('VivoBridgeDiagnostics.failureCategory(it)', identity)
        self.assertEqual(1, source.count('installed.compareAndSet(false, true)'))
        self.assertNotIn('installed.set(', source)
        self.assertNotIn('installed.compareAndSet(true, false)', source)
        for gate in ('hooks.report.installedCount == 7', 'hooks.report.failedCount == 0',
                     'hooks.report.missingCount == 0', 'hooks.report.skippedCount == 0'):
            self.assertIn(gate, source)
        self.assertIn('if (ready) {', source)
        self.assertIn('VivoBridgeDiagnostics.Stage.HOOK_READY', source)
        self.assertLess(source.index('registerBusiness(module, rootLogger, api, state)'), source.index('Stage.HOOK_READY'))
        self.assertLess(source.index('Prefs.registerRemoteListener(listener)'), source.index('state.ready = ready'))
        self.assertLess(source.index('state.ready = ready'), source.index('Stage.HOOK_READY'))
        self.assertIn('VivoBridgeDiagnostics.Failure.REGISTRATION', source)

    def test_diagnostics_accept_only_enums_and_do_not_log_dynamic_values(self):
        source = (MAIN / 'kotlin/io/github/mangi/eta/agent/vivo/VivoBridgeDiagnostics.kt').read_text()
        for argument in ('stage: Stage', 'failure: Failure? = null', 'reason: Reason? = null',
                         'modelFailure: VivoModelFailureClassifier.Classification? = null',
                         'phase: FailurePhase? = null', 'terminalCode: TerminalCode? = null'):
            self.assertIn(argument, source)
        self.assertIn('enum class Reason {', source)
        self.assertIn('totalLimit = 80, perStageLimit = 4', source)
        self.assertIn('budget.claim(stage.ordinal) ?: return@runCatching', source)
        self.assertEqual(['i'], re.findall(r'Log\.([a-z]+)\(', source))
        self.assertIn('Log.i("EtaVivoText", "v=1 stage=${stage.name} n=$count$category$rejection$modelCategory$http$modelCode$location$terminal")', source)
        for field in ('error=${it.category.name}', 'http_code=$it', 'model_code=${it.name}',
                      'phase=${it.name}', 'code=${it.name}'):
            self.assertIn(field, source)
        self.assertIn('takeIf { it in 100..599 }', source)
        self.assertIn('" failure=${it.name}"', source)
        self.assertIn('" reason=${it.name}"', source)
        for forbidden in ('.message', '.localizedMessage', '.stackTrace', '.cause',
                          '.javaClass', '.toString(', 'printStackTrace', 'Log.e(', 'Log.w('):
            self.assertNotIn(forbidden, source)
        for category in ('CLASS', 'METHOD', 'FIELD', 'LINKAGE', 'REGISTRATION', 'OTHER'):
            self.assertIn(category, source)
        hooks = (MAIN / 'kotlin/io/github/mangi/eta/hook/vivo/VivoHooks.kt').read_text()
        callback = hooks.split('intercept("vivo.bootstrap"', 1)[1].split(
            'internal fun <T> bootstrapCopilotOnCreate', 1)[0]
        self.assertEqual(['"本机文本接管初始化失败，未启用接管"'], re.findall(r'\.warn\((.*?)\)', callback))
        self.assertNotIn('Log.', callback)
        self.assertNotIn('it.message', callback)
        self.assertNotIn('it.toString()', callback)

    def test_mapper_capture_is_not_restricted_to_remote_query_label(self):
        source = (MAIN / "kotlin/io/github/mangi/eta/hook/vivo/VivoHooks.kt").read_text()
        typed = source.split("intercept(\"vivo.typed-query\"", 1)[1].split("intercept(\"vivo.outbound\"", 1)[0]
        self.assertNotIn("chain.args.getOrNull(0) == \"remote query\"", typed)
        self.assertIn("api.candidate(mapped, chain.args.getOrNull(1))?.let { state.capture(mapped!!, it) }", typed)
        self.assertEqual(1, typed.count('chain.proceed()'))
        self.assertIn('val mapped = chain.proceed()', typed)

    def test_candidate_reader_is_the_production_path_with_runtime_object_gates(self):
        hooks = (MAIN / 'kotlin/io/github/mangi/eta/hook/vivo/VivoHooks.kt').read_text()
        reader = (MAIN / 'kotlin/io/github/mangi/eta/hook/vivo/VivoCandidateReader.kt').read_text()
        self.assertIn('private val candidateReader = VivoCandidateReader(request, model, payload,', hooks)
        candidate = hooks.split('fun candidate(mapped:', 1)[1].split('private fun hide(', 1)[0]
        self.assertIn('candidateReader.read(', candidate)
        self.assertIn('mapped, value, Prefs.isEnabled(Prefs.Keys.AGENT_REQUIRE_PREFIX)', candidate)
        self.assertIn('is VivoCandidateReader.Accepted -> Candidate(result.dialog, result.conversation, result.prompt)', candidate)
        self.assertIn('is VivoCandidateReader.Rejected -> {', candidate)
        self.assertEqual(1, candidate.count('VivoBridgeDiagnostics.record('))
        self.assertIn('VivoBridgeDiagnostics.record(stage, reason = result.reason)', candidate)
        for stage in ('MAPPER_SHAPE_REJECTED', 'MAPPER_IDS_REJECTED', 'MAPPER_PROMPT_REJECTED'):
            self.assertIn('VivoBridgeDiagnostics.Stage.' + stage, candidate)
        for value, kind in (('mapped', 'MAPPED'), ('value', 'REQUEST'), ('modelValue', 'MODEL')):
            if value != 'modelValue':
                self.assertIn(f'if ({value} == null) return Rejected(Reason.{kind}_NULL)', reader)
            else:
                self.assertIn('?: return Rejected(Reason.MODEL_NULL)', reader)
        for owner, value, reason in (('payload', 'mapped', 'MAPPED_TYPE'),
                                     ('request', 'value', 'REQUEST_TYPE'),
                                     ('model', 'modelValue', 'MODEL_TYPE')):
            self.assertIn(f'if (!{owner}.isInstance({value})) return Rejected(Reason.{reason})', reader)
        self.assertLess(reader.index('!model.isInstance(modelValue)'), reader.index('modelGetters.getValue(name).invoke(modelValue)'))
        self.assertNotIn('returnType ==', reader)
        self.assertIn('data class Rejected(val reason: Reason) : Result', reader)
        self.assertNotIn('Log.', reader)
        self.assertNotIn('VivoBridgeDiagnostics.record(', reader)
        for forbidden in ('.message', '.localizedMessage', '.stackTrace', '.javaClass', '.toString(', 'printStackTrace'):
            self.assertNotIn(forbidden, candidate + reader)

    def test_candidate_strings_are_strict_and_specialized_contents_are_not_relaxed(self):
        reader = (MAIN / 'kotlin/io/github/mangi/eta/hook/vivo/VivoCandidateReader.kt').read_text()
        self.assertNotIn('as? String', reader)
        self.assertIn('if (value != null && value !is String) throw InvalidType(reason)', reader)
        self.assertIn('return value as String?', reader)
        for getter, reason in (('getAgentId', 'AGENT_ID_TYPE'), ('getBizSource', 'BIZ_SOURCE_TYPE'),
                              ('getScheduleContext', 'SCHEDULE_CONTEXT_TYPE'), ('getBotType', 'BOT_TYPE_TYPE'),
                              ('getDisplayQuery', 'DISPLAY_QUERY_TYPE'), ('getServerQuery', 'SERVER_QUERY_TYPE')):
            self.assertIn(f'nullableString(get("{getter}"), Reason.{reason})', reader)
        for getter, reason in (('getDialogId', 'DIALOG_ID_TYPE'), ('getConversationId', 'CONVERSATION_ID_TYPE')):
            self.assertIn(f'nullableString(requestGetters.getValue("{getter}").invoke(value), Reason.{reason})', reader)
        self.assertIn('required<Int>(get("getInputType"), Reason.INPUT_TYPE_TYPE)', reader)
        for getter in ('getRenderText', 'getShortcut', 'getRegenerate', 'getSkipRemote', 'getFromRecommend'):
            self.assertIn(f'required<Boolean>(get("{getter}")', reader)
        self.assertIn('if (value !is T) throw InvalidType(reason)', reader)
        for getter, reason in (('getAttachmentQueryModel', 'ATTACHMENT'), ('getCameraContext', 'CAMERA_CONTEXT'),
                              ('getPsAgentContext', 'PS_AGENT_CONTEXT'), ('getTwsNotificationContext', 'TWS_NOTIFICATION_CONTEXT'),
                              ('getExtraParams', 'EXTRA_PARAMS')):
            self.assertIn(f'"{getter}" to Reason.{reason}', reader)
        self.assertIn('if (get(name) != null) return Rejected(reason)', reader)
        self.assertIn('if (!nullableString(get("getScheduleContext"), Reason.SCHEDULE_CONTEXT_TYPE).isNullOrBlank())', reader)
        self.assertIn('val botType = nullableString(get("getBotType"), Reason.BOT_TYPE_TYPE)', reader)
        self.assertIn('if (!botType.isNullOrBlank() && botType != "main") {\n'
                      '            return Rejected(Reason.BOT_TYPE)\n'
                      '        }', reader)
        bot_gate = reader.split('val botType = ', 1)[1].split('get("getIntentions")', 1)[0]
        for forbidden in ('startsWith(', 'contains(', 'lowercase(', 'uppercase(', 'trim(', 'ignoreCase'):
            self.assertNotIn(forbidden, bot_gate)
        self.assertIn('listOf("first", "second", "third")', reader)
        self.assertIn('if (!intentions.isInstance(obj)) return Rejected(Reason.INTENTIONS_TYPE)', reader)
        self.assertIn('if (!nullableString(field.get(obj), Reason.INTENTION_TEXT_TYPE).isNullOrBlank())', reader)
        self.assertIn('if (!newQueryParams.isInstance(obj)) return Rejected(Reason.NEW_QUERY_PARAMS_TYPE)', reader)
        self.assertIn('if (extraQuery.get(obj) != null) return Rejected(Reason.NEW_QUERY_PARAMS)', reader)
        self.assertIn('VivoNativePolicy.rejection(shape)?.let { return Rejected(it) }', reader)
        self.assertIn('VivoNativePolicy.prompt(visible, requirePrefix) ?: return Rejected(Reason.PROMPT)', reader)
        self.assertLess(reader.index('if (extraQuery.get(obj) != null)'), reader.index('return Accepted('))

    def test_only_exact_bottom_input_extends_source_policy_and_other_gates_remain(self):
        policy = (MAIN / 'kotlin/io/github/mangi/eta/hook/vivo/VivoNativePolicy.kt').read_text()
        self.assertIn('fun eligible(s: Shape) = rejection(s) == null', policy)
        self.assertIn('!s.bizSource.isNullOrEmpty() && s.bizSource != "BottomInput" -> Reason.BIZ_SOURCE', policy)
        for gate in ('s.agentId != "little_v"', 's.inputType != 0', '!s.renderText', 's.shortcut',
                     's.regenerate', 's.skipRemote', 's.recommended', 's.specialized'):
            self.assertIn(gate + ' -> Reason.', policy)
        source_gate = policy.split('fun rejection(', 1)[1].split('fun prompt(', 1)[0]
        for forbidden in ('startsWith(', 'contains(', 'lowercase(', 'uppercase(', 'trim(', 'ignoreCase'):
            self.assertNotIn(forbidden, source_gate)

    def test_outbound_requires_mapper_identity_and_has_no_link_only_fallback(self):
        source = (MAIN / "kotlin/io/github/mangi/eta/hook/vivo/VivoHooks.kt").read_text()
        query_start = source.split('intercept("vivo.query-start"', 1)[1].split('intercept("vivo.typed-query"', 1)[0]
        self.assertIn("state.begin(link, dialog)", query_start)
        for forbidden in ("captureForLink", "queryCandidate", "pending", "state.take("):
            self.assertNotIn(forbidden, source)
        outbound = source.split('intercept("vivo.outbound"', 1)[1].split("for ((name, linkIndex", 1)[0]
        self.assertIn("state.receipt(it)", outbound)
        self.assertIn("turn.link == link", outbound)
        self.assertIn("suppressOwnedVivoOutbound(", outbound)
        self.assertEqual(1, outbound.count("chain.proceed()"))
        self.assertIn("proceed = { chain.proceed() }", outbound)
        helper = (MAIN / "kotlin/io/github/mangi/eta/hook/vivo/VivoOutboundSuppression.kt").read_text()
        self.assertIn("if (!owned) return proceed()", helper)
        self.assertEqual(1, helper.count("proceed()"))
        self.assertIn("runCatching { onFailure(error) }", helper)

    def test_candidate_rejections_and_missing_identity_have_fixed_diagnostics(self):
        source = (MAIN / "kotlin/io/github/mangi/eta/hook/vivo/VivoHooks.kt").read_text()
        for stage in ("QUERY_ENTERED", "QUERY_GATE_CLOSED", "QUERY_NON_REMOTE", "QUERY_IDS_REJECTED",
                      "QUERY_REFLECTION_FAILED", "QUERY_BEGUN", "MAPPER_ENTERED", "MAPPER_GATE_CLOSED",
                      "MAPPER_SHAPE_REJECTED", "MAPPER_PROMPT_REJECTED", "MAPPER_IDS_REJECTED",
                      "MAPPER_REFLECTION_FAILED", "MAPPER_TURN_MISSING", "MAPPER_BOUND",
                      "OUTBOUND_ENTERED", "OUTBOUND_RECEIPT_MISSING", "OUTBOUND_LINK_MISMATCH"):
            self.assertIn("VivoBridgeDiagnostics.Stage." + stage, source)
        self.assertNotIn("Log.", source)

    def test_selection_is_read_only_and_body_overrides_fail_closed(self):
        source = (MAIN / 'kotlin/io/github/mangi/eta/agent/vivo/VivoTextModelGateway.kt').read_text()
        for forbidden in ('currentRuntimeConfig()', 'repairSelection(', 'selectedOrFirstModel(',
                          'ensureBuiltInsMerged(', 'setSelection('):
            self.assertNotIn(forbidden, source)
        self.assertIn('configForProviderAndModel(provider, model)', source)
        self.assertIn('config.customBody.isNotEmpty()', source)
        self.assertIn('config.extraBodyJson.isNotBlank()', source)
        self.assertIn('selected != readSelection()', source)

    def test_typed_identity_seam_and_no_cloud_retry_after_claim(self):
        source = (MAIN / 'kotlin/io/github/mangi/eta/hook/vivo/VivoHooks.kt').read_text()
        self.assertIn('WeakIdentityReceipts<Candidate>', source)
        self.assertIn('vivo.query-start', source)
        self.assertNotIn('pending.remove(payload)', source)
        self.assertIn('LinkServer.m(ChatPayload,linkId,callback)', source)
        self.assertNotIn('JSONObject(', source)
        self.assertNotIn('invokeOriginalMethod', source)
        self.assertNotIn('getPayload', source)
        self.assertIn('active.put(link, owned)', source)
        self.assertIn('if (!stillOwns(owned))', source)
        self.assertIn('@Synchronized fun claim', source)
        self.assertIn('@Synchronized fun cancel', source)
        client = (MAIN / 'kotlin/io/github/mangi/eta/agent/vivo/VivoTextBridgeClient.kt').read_text()
        self.assertLess(client.index('if (!owns())'), client.index('state.begin(id)'))
        self.assertIn('state.markSent(ticket, owns())', client)
        scope = (MAIN / 'resources/META-INF/xposed/scope.list').read_text().splitlines()
        self.assertEqual(1, scope.count('com.vivo.ai.copilot'))
        self.assertNotIn('com.vivo.ai.gptagent', scope)
        policy = (MAIN / 'kotlin/io/github/mangi/eta/core/ModuleConfig.kt').read_text()
        self.assertNotIn('com.vivo.ai.copilot', policy)


if __name__ == '__main__':
    unittest.main()
