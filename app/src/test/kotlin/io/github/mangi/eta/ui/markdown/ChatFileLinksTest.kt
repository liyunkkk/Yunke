package io.github.mangi.eta.ui.markdown

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatFileLinksTest {
    @Test fun resolvesLocalPathsAndFileUrisWithoutTreatingWebAsFile() {
        assertEquals("/storage/emulated/0/Download/a.apk", chatLocalFilePath("/storage/emulated/0/Download/a.apk"))
        assertEquals("/sdcard/Download/a b.apk", chatLocalFilePath("file:///sdcard/Download/a%20b.apk"))
        assertNull(chatLocalFilePath("https://example.com/a.apk"))
        assertNull(chatLocalFilePath("file://server/a.apk"))
        assertNull(chatLocalFilePath("//example.com/a.apk"))
        assertNull(chatLocalFilePath("/sdcard/a\u0000.apk"))
    }
    @Test fun apkUsesInstallerMimeAndDocumentsKeepTheirMime() {
        assertEquals("application/vnd.android.package-archive", chatFileMime("a.APK"))
        assertEquals("application/pdf", chatFileMime("a.pdf"))
        assertEquals("text/plain", chatFileMime("a.md"))
    }
}
