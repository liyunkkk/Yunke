package io.github.mangi.eta.agent.voice.doubao

import android.app.Application
import java.time.Instant
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class VoiceProjectCatalogTest {
    @Test fun signedGetUsesIamHostScopeAndSortedPaginationQuery() {
        val request = VoiceProjectCatalog.signedRequest("test-ak", "test-secret", 0, Instant.parse("2026-09-20T03:00:00Z"))
        assertEquals("GET", request.method)
        assertNull(request.body)
        assertEquals("iam.volcengineapi.com", request.url.host)
        assertEquals(request.url.host, request.header("Host"))
        assertEquals("Action=ListProjects&Limit=100&Offset=0&Version=2021-08-01", request.url.encodedQuery)
        assertEquals("20260920T030000Z", request.header("X-Date"))
        // Independently calculated using Python hashlib/hmac and the documented canonical format.
        assertEquals("HMAC-SHA256 Credential=test-ak/20260920/cn-beijing/iam/request, SignedHeaders=host;x-date, Signature=dc74ecb2620c0cec47e08b8b5d18deff4f61e71bb52769fadda85e95136e420e", request.header("Authorization"))
        assertNull(request.header("X-Api-Key"))
        assertFalse(request.url.toString().contains("test-secret"))
        assertFalse(request.header("Authorization")!!.contains("test-secret"))
    }

    @Test fun paginationOffsetIsSignedAndNegativeOffsetRejected() {
        val now = Instant.parse("2026-09-20T03:00:00Z")
        val first = VoiceProjectCatalog.signedRequest("ak", "sk", 0, now)
        val next = VoiceProjectCatalog.signedRequest("ak", "sk", 100, now)
        assertEquals("100", next.url.queryParameter("Offset"))
        assertNotEquals(first.header("Authorization"), next.header("Authorization"))
        assertThrows(IllegalArgumentException::class.java) { VoiceProjectCatalog.signedRequest("ak", "sk", -1, now) }
    }

    @Test fun parsesActualNamesDisplayNamesAndPermissions() {
        val page = VoiceProjectCatalog.parsePage(JSONObject("""{"Result":{"Total":3,"Projects":[
            {"ProjectName":"default","DisplayName":"默认项目","HasPermission":true},
            {"ProjectName":"restricted","DisplayName":"","HasPermission":false},
            {"ProjectName":"unknown"}]}}"""))
        assertEquals(3, page.count)
        assertEquals(3, page.total)
        assertEquals(VoiceProjectCatalog.Project("default", "默认项目", true), page.projects[0])
        assertEquals("restricted", page.projects[1].displayName)
        assertFalse(page.projects[1].allowed)
        assertFalse(page.projects[2].allowed)
    }

    @Test fun malformedResponseDoesNotMasqueradeAsEmptyList() {
        assertThrows(JSONException::class.java) { VoiceProjectCatalog.parsePage(JSONObject("{}")) }
        assertThrows(JSONException::class.java) { VoiceProjectCatalog.parsePage(JSONObject("""{"Result":{"Total":0}}""")) }
        assertThrows(IllegalStateException::class.java) {
            VoiceProjectCatalog.parsePage(JSONObject("""{"Result":{"Total":1,"Projects":[{"ProjectName":" "}]}}"""))
        }
    }

    @Test fun emptyAccountReturnsAnEmptyListWithoutInventingDefaultProject() {
        val pages = mutableListOf<Int>()
        val result = VoiceProjectCatalog.collectPages { offset ->
            pages.add(offset)
            VoiceProjectCatalog.parsePage(JSONObject("""{"Result":{"Total":0,"Projects":[]}}"""))
        }
        assertTrue(result.isEmpty())
        assertEquals(listOf(0), pages)
    }

    @Test fun fetchesAllPagesAndDeduplicatesByActualProjectName() {
        val offsets = mutableListOf<Int>()
        val result = VoiceProjectCatalog.collectPages { offset ->
            offsets.add(offset)
            if (offset == 0) VoiceProjectCatalog.Page((0 until 100).map { VoiceProjectCatalog.Project("p$it", "p$it", true) }, 100, 102)
            else VoiceProjectCatalog.Page(listOf(
                VoiceProjectCatalog.Project("p99", "updated", true),
                VoiceProjectCatalog.Project("last", "last", false)), 2, 102)
        }
        assertEquals(listOf(0, 100), offsets)
        assertEquals(101, result.size)
        assertEquals("updated", result.first { it.name == "p99" }.displayName)
        assertEquals("last", result.last().name)
    }

    @Test fun incompletePageIsRejectedRatherThanShowingPartialChoices() {
        assertThrows(IllegalStateException::class.java) {
            VoiceProjectCatalog.collectPages { VoiceProjectCatalog.Page(emptyList(), 0, 2) }
        }
    }

    @Test fun pageLimitIsBoundedAndDoesNotReturnPartialResults() {
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            VoiceProjectCatalog.collectPages {
                calls++
                VoiceProjectCatalog.Page(emptyList(), 100, 3000)
            }
        }
        assertEquals(20, calls)
    }

    @Test fun errorOutputRedactsCredentials() {
        val root = JSONObject("""{"ResponseMetadata":{"RequestId":"request-1","Error":{"Code":"AccessDenied","Message":"denied secret-ak secret-sk"}}}""")
        val text = DoubaoVoiceCatalog.responseError(403, root, listOf("secret-ak", "secret-sk"))!!
        assertTrue(text.contains("IAM"))
        assertFalse(text.contains("secret-ak"))
        assertFalse(text.contains("secret-sk"))
    }
}
