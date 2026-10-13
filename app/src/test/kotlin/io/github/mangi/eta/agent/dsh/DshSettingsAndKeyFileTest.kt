package io.github.mangi.eta.agent.dsh

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DshSettingsAndKeyFileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun dshSettingsSaveAndLoadRoundTrip() {
        val context = RuntimeEnvironment.getApplication()

        // 验证初始加载回退到官方默认值
        val initialSettings = DshSettings.load(context)
        assertEquals("https://wb2.c44lyk.dpdns.org/v1", initialSettings.endpoint)
        assertEquals("/root/.dsh/key", initialSettings.keyFilePath)
        assertEquals("deepseek-v4-flash", initialSettings.model)
        assertEquals("high", initialSettings.effort)

        // 写入自定义配置
        DshSettings.save(
            context = context,
            endpoint = "https://custom.endpoint.org/v1",
            model = "deepseek-coder",
            effort = "medium",
            keyFilePath = "/root/.dsh/key",
            apiKey = "sk-test-123456",
        )

        // 再次加载，核对 SharedPreferences 往返一致性
        val loadedSettings = DshSettings.load(context)
        assertEquals("https://custom.endpoint.org/v1", loadedSettings.endpoint)
        assertEquals("/root/.dsh/key", loadedSettings.keyFilePath)
        assertEquals("deepseek-coder", loadedSettings.model)
        assertEquals("medium", loadedSettings.effort)

        // 校验底层 Preference Key 名未偏离既有契约
        val prefs = context.getSharedPreferences(DshSettings.PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals("https://custom.endpoint.org/v1", prefs.getString(DshSettings.PREF_ENDPOINT, null))
        assertEquals("deepseek-coder", prefs.getString(DshSettings.PREF_MODEL, null))
        assertEquals("medium", prefs.getString(DshSettings.PREF_EFFORT, null))
        assertEquals("/root/.dsh/key", prefs.getString(DshSettings.PREF_KEY_FILE_PATH, null))
        assertEquals("sk-test-123456", prefs.getString(DshSettings.PREF_API_KEY, null))
    }

    @Test
    fun keyFileContentFormatStrictlyMatchesContract() {
        val content = DshKeyFileManager.formatKeyFileContent(
            endpoint = "https://wb2.c44lyk.dpdns.org/v1",
            apiKey = "sk-secret-token",
        )

        val expected = "DEEPSEEK_BASE_URL=https://wb2.c44lyk.dpdns.org/v1\nDEEPSEEK_API_KEY=sk-secret-token\n"
        assertEquals(expected, content)

        // 验证行数严格为 2 行
        val lines = content.trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("DEEPSEEK_BASE_URL="))
        assertTrue(lines[1].startsWith("DEEPSEEK_API_KEY="))
    }

    @Test
    fun keyFileWriteAndParseWithPermissions() {
        val keyFile = File(tempFolder.newFolder("dsh"), "key")

        val success = DshKeyFileManager.writeKeyFile(
            file = keyFile,
            endpoint = "https://api.deepseek.com/v1",
            apiKey = "sk-my-api-key",
        )

        assertTrue("writeKeyFile 必须返回成功", success)
        assertTrue("密钥文件必须存在", keyFile.isFile)
        assertTrue("文件对属主可读", keyFile.canRead())
        assertTrue("文件对属主可写", keyFile.canWrite())

        // 校验文件内容
        val lines = keyFile.readLines(Charsets.UTF_8)
        assertEquals(2, lines.size)
        assertEquals("DEEPSEEK_BASE_URL=https://api.deepseek.com/v1", lines[0])
        assertEquals("DEEPSEEK_API_KEY=sk-my-api-key", lines[1])

        // 校验解析逻辑
        val parsed = DshKeyFileManager.parseKeyFile(keyFile)
        assertNotNull(parsed)
        assertEquals("https://api.deepseek.com/v1", parsed?.first)
        assertEquals("sk-my-api-key", parsed?.second)
    }

    @Test
    fun readApiKeyPrefersFileAndFallsBackToPrefs() {
        val context = RuntimeEnvironment.getApplication()
        val keyFile = File(tempFolder.newFolder("dsh_fallback"), "key")

        // 1. 无文件无 prefs 时返回空
        assertEquals("", DshKeyFileManager.readApiKey(context, keyFile))

        // 2. 仅写入 prefs
        context.getSharedPreferences(DshSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(DshSettings.PREF_API_KEY, "sk-pref-only")
            .commit()
        assertEquals("sk-pref-only", DshKeyFileManager.readApiKey(context, keyFile))

        // 3. 写入文件后优先从文件读取
        DshKeyFileManager.writeKeyFile(
            file = keyFile,
            endpoint = "https://api.deepseek.com/v1",
            apiKey = "sk-file-preferred",
        )
        assertEquals("sk-file-preferred", DshKeyFileManager.readApiKey(context, keyFile))
    }

    @Test
    fun dshSettingsDoesNotExposeApiKeyField() {
        // 静态保障：DshSettings 类本身不包含 apiKey 字段，避免任何命令行参数或环境泄露
        val fields = DshSettings::class.java.declaredFields.map { it.name }
        assertFalse("DshSettings 数据类字段中不得包含 apiKey", fields.contains("apiKey"))
        assertFalse("DshSettings 数据类字段中不得包含 api_key", fields.contains("api_key"))
    }
}
