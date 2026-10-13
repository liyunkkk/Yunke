package io.github.mangi.eta.agent.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DshSessionBindingStoreTest {

    @Test
    fun inMemoryStoreInitiallyEmptyAndSavesBindings() {
        val store = InMemoryDshSessionBindingStore()
        assertTrue("初始应为空映射", store.load().isEmpty())

        store.save(mapOf("conv-1" to "session-abc", "conv-2" to "session-xyz"))
        val loaded = store.load()

        assertEquals(2, loaded.size)
        assertEquals("session-abc", loaded["conv-1"])
        assertEquals("session-xyz", loaded["conv-2"])

        // 覆盖更新
        store.save(mapOf("conv-1" to "session-updated"))
        val updated = store.load()
        assertEquals(1, updated.size)
        assertEquals("session-updated", updated["conv-1"])
    }

    @Test
    fun fileStorePersistsAndReloadsAcrossInstances() {
        val context = RuntimeEnvironment.getApplication()
        val bindingFile = File(context.filesDir, FileDshSessionBindingStore.FILE_NAME)
        if (bindingFile.exists()) bindingFile.delete()

        val firstStore = FileDshSessionBindingStore(context)
        assertTrue("文件不存在时应安全返回空映射", firstStore.load().isEmpty())

        val bindings = mapOf(
            "conv-a" to "session-100",
            "conv-b" to "session-200",
        )
        firstStore.save(bindings)

        assertTrue("保存后文件应落盘", bindingFile.exists())

        // 模拟跨实例/进程重启重新读取
        val secondStore = FileDshSessionBindingStore(context)
        val loaded = secondStore.load()

        assertEquals(bindings, loaded)
    }

    @Test
    fun fileStoreHandlesCorruptedJsonGracefully() {
        val context = RuntimeEnvironment.getApplication()
        val bindingFile = File(context.filesDir, FileDshSessionBindingStore.FILE_NAME)
        bindingFile.writeText("invalid json content", Charsets.UTF_8)

        val store = FileDshSessionBindingStore(context)
        val loaded = store.load()

        assertTrue("损坏的 JSON 应该回退为空映射而不抛异常", loaded.isEmpty())
    }
}
