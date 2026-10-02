package io.github.mangi.eta.hook.vivo

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.mangi.eta.agent.vivo.VivoBridgeDiagnostics
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** CopilotApp.onCreate can block the main thread; takeover must be armed before that call returns. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class VivoOnCreateBootstrapTest {
    private lateinit var lines: AtomicInteger
    private var previousCount = 0
    private lateinit var context: Application

    @Before fun isolateDiagnostics() {
        lines = VivoBridgeDiagnostics::class.java.getDeclaredField("lines").apply {
            isAccessible = true
        }.get(null) as AtomicInteger
        previousCount = lines.getAndSet(0)
        ShadowLog.clear()
        context = Application()
    }

    @After fun restoreDiagnostics() {
        lines.set(previousCount)
        ShadowLog.clear()
    }

    @Test fun hookReadyIsLoggedBeforeBlockedOnCreateReturns() {
        val seenWhileBlocked = mutableListOf<String>()
        var installs = 0
        val result = bootstrap(
            install = {
                installs++
                true
            },
            proceed = {
                seenWhileBlocked.addAll(stages())
                "vendor-result"
            },
        )
        assertEquals("vendor-result", result)
        assertEquals(1, installs)
        assertEquals(
            listOf("BOOTSTRAP_ENTERED", "BOOTSTRAP_PREINIT_SUCCEEDED", "HOOK_READY"),
            seenWhileBlocked,
        )
        assertEquals(seenWhileBlocked + "BOOTSTRAP_ORIGINAL_RETURNED", stages())
    }

    @Test fun identityOrContextFailureDoesNotRegisterBusinessHooks() {
        var installs = 0
        var identityCalls = 0
        assertEquals("once", bootstrap(
            resolve = { null },
            identity = { identityCalls++; true },
            install = { installs++; true },
            proceed = { "once" },
        ))
        assertEquals(0, identityCalls)
        assertEquals(0, installs)
        assertEquals(
            listOf("BOOTSTRAP_ENTERED", "BOOTSTRAP_CONTEXT_UNAVAILABLE", "BOOTSTRAP_PREINIT_FAILED",
                "BOOTSTRAP_ORIGINAL_RETURNED"),
            stages(),
        )

        ShadowLog.clear()
        lines.set(0)
        assertEquals("rejected", bootstrap(
            identity = { false },
            install = { installs++; true },
            proceed = { "rejected" },
        ))
        assertEquals(0, installs)
        assertEquals(
            listOf("BOOTSTRAP_ENTERED", "BOOTSTRAP_IDENTITY_REJECTED", "BOOTSTRAP_PREINIT_FAILED",
                "BOOTSTRAP_ORIGINAL_RETURNED"),
            stages(),
        )

        ShadowLog.clear()
        lines.set(0)
        var claims = 0
        bootstrap(identity = { null }, tryClaim = { claims++; true }, install = { installs++; true })
        assertEquals(0, claims)
        assertEquals(0, installs)
        assertEquals(listOf("BOOTSTRAP_ENTERED", "BOOTSTRAP_PREINIT_FAILED", "BOOTSTRAP_ORIGINAL_RETURNED"), stages())
    }

    @Test fun originalExceptionIsDiagnosedWithoutWrappingOrRegisteringAfterRejection() {
        val vendor = IllegalStateException("vendor-oncreate-should-not-be-logged")
        var installs = 0
        var proceeds = 0
        val rejected = try {
            bootstrap(
                identity = { false },
                install = { installs++; true },
                proceed = {
                    proceeds++
                    throw vendor
                },
            )
            null
        } catch (error: Throwable) {
            error
        }
        assertSame(vendor, rejected)
        assertEquals(0, installs)
        assertEquals(1, proceeds)
        assertEquals(
            listOf("BOOTSTRAP_ENTERED", "BOOTSTRAP_IDENTITY_REJECTED", "BOOTSTRAP_PREINIT_FAILED",
                "BOOTSTRAP_ORIGINAL_THREW"),
            stages(),
        )
        val threw = ShadowLog.getLogsForTag("EtaVivoText").last()
        assertEquals(Log.INFO, threw.type)
        assertEquals("v=1 stage=BOOTSTRAP_ORIGINAL_THREW n=4 failure=OTHER", threw.msg)
        assertNull(threw.throwable)
        assertFalse(threw.msg.contains("vendor-oncreate"))

        ShadowLog.clear()
        lines.set(0)
        proceeds = 0
        val armed = try {
            bootstrap(
                install = { installs++; true },
                proceed = {
                    assertEquals(1, installs)
                    assertEquals(
                        listOf("BOOTSTRAP_ENTERED", "BOOTSTRAP_PREINIT_SUCCEEDED", "HOOK_READY"),
                        stages(),
                    )
                    proceeds++
                    throw vendor
                },
            )
            null
        } catch (error: Throwable) {
            error
        }
        assertSame(vendor, armed)
        assertEquals(1, installs)
        assertEquals(1, proceeds)
        assertEquals(
            listOf("BOOTSTRAP_ENTERED", "BOOTSTRAP_PREINIT_SUCCEEDED", "HOOK_READY", "BOOTSTRAP_ORIGINAL_THREW"),
            stages(),
        )
    }

    @Test fun registrationFailureStillCallsOriginalOnceAndDoesNotMarkReady() {
        var warnings = 0
        var proceeds = 0
        assertEquals("continued", bootstrap(
            install = { throw LinkageError("abi") },
            onFailure = { warnings++ },
            proceed = { proceeds++; "continued" },
        ))
        assertEquals(1, warnings)
        assertEquals(1, proceeds)
        assertEquals(
            listOf("BOOTSTRAP_ENTERED", "BOOTSTRAP_INIT_FAILED", "BOOTSTRAP_PREINIT_FAILED",
                "BOOTSTRAP_ORIGINAL_RETURNED"),
            stages(),
        )
        assertTrue(ShadowLog.getLogsForTag("EtaVivoText")[1].msg.contains("failure=LINKAGE"))
        assertFalse(stages().contains("HOOK_READY"))
    }

    @Test fun sourceArmsExactIdentityGateBeforeTheSingleOriginalCall() {
        val hooks = source("src/main/kotlin/io/github/mangi/eta/hook/vivo/VivoHooks.kt")
        val policy = source("src/main/kotlin/io/github/mangi/eta/agent/vivo/VivoTextBridgePolicy.kt")
        val callback = hooks.substring(hooks.indexOf("intercept(\"vivo.bootstrap\""),
            hooks.indexOf("internal fun <T> bootstrapCopilotOnCreate"))
        val helper = hooks.substring(hooks.indexOf("internal fun <T> bootstrapCopilotOnCreate"),
            hooks.indexOf("private fun enabled()"))
        assertEquals(1, Regex("""chain\.proceed\(\)""").findAll(callback).count())
        assertTrue(callback.indexOf("applicationContext") < callback.indexOf("chain.proceed()"))
        assertTrue(callback.indexOf("::supported") < callback.indexOf("chain.proceed()"))
        assertTrue(callback.indexOf("registerBusiness(") < callback.indexOf("chain.proceed()"))
        assertFalse(callback.contains("finally {"))
        assertFalse(helper.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//.*"), "").contains("finally"))
        assertTrue(helper.indexOf("BOOTSTRAP_ENTERED") < helper.indexOf("armBeforeOriginal("))
        assertTrue(helper.indexOf("armBeforeOriginal(") < helper.indexOf("return callOriginalOnCreate(proceed)"))
        assertTrue(helper.indexOf("installHooks(context)") < helper.indexOf("BOOTSTRAP_PREINIT_SUCCEEDED"))
        assertTrue(helper.indexOf("BOOTSTRAP_PREINIT_SUCCEEDED") < helper.indexOf("HOOK_READY"))
        assertTrue(helper.contains("BOOTSTRAP_ORIGINAL_THREW"))
        assertTrue(helper.contains("throw error"))
        assertEquals(1, Regex("""\bproceed\(\)""").findAll(helper).count())
        assertTrue(hooks.contains("Build.MODEL != \"V2419A\" || Build.VERSION.SDK_INT != 35"))
        assertTrue(hooks.contains("PackageManager.GET_SIGNING_CERTIFICATES"))
        assertTrue(hooks.contains("VivoTextBridgePolicy.callerAllowed"))
        assertTrue(hooks.contains("BOOTSTRAP_IDENTITY_QUERY_FAILED"))
        assertTrue(policy.contains("const val SIGNER = \"915191fccf5058fa4b21c9c8ea8897040d313d18838850e986fc00055117d1db\""))
        assertTrue(policy.contains("version == 6090021L && signers == listOf(SIGNER)"))
        assertTrue(policy.contains("uid in 10000..19999 && packages == listOf(PACKAGE)"))
    }

    private fun bootstrap(
        resolve: (Any?) -> Context? = { context },
        identity: (Context) -> Boolean? = { true },
        tryClaim: () -> Boolean = { true },
        install: (Context) -> Boolean = { error("business hooks must not be installed") },
        onFailure: () -> Unit = { error("unexpected registration failure") },
        proceed: () -> Any? = { "ok" },
    ): Any? = VivoHooks.bootstrapCopilotOnCreate(
        thisObject = context,
        resolveContext = resolve,
        identity = identity,
        tryClaim = tryClaim,
        installHooks = install,
        onRegistrationFailure = onFailure,
        proceed = proceed,
    )

    private fun stages(): List<String> = ShadowLog.getLogsForTag("EtaVivoText").map { log ->
        assertEquals(Log.INFO, log.type)
        assertNull(log.throwable)
        Regex("stage=([A-Z0-9_]+)").find(log.msg)!!.groupValues[1]
    }

    private fun source(relative: String): String {
        val file = listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
