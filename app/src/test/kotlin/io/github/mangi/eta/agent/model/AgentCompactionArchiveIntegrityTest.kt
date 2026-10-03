package io.github.mangi.eta.agent.model

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentCompactionArchiveIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "11111111-1111-4111-8111-111111111111"
    private val marker = "[Eta tool output pruned; original: context-checkpoint:$id; read_compacted_history]"
    private fun tool(content: String) = AgentModelClient.ConversationMessage("tool", content, toolCallId = "call")

    @Test fun markerRecognitionAndPositionValidationUseTheSameContract() {
        val ref = AgentCompactionArchiveIntegrity.toolReference(tool("head\n$marker\ntail"))!!
        assertEquals(id, ref.id)
        assertEquals("head", ref.head)
        assertEquals("tail", ref.tail)
        for (content in listOf(marker, "head $marker tail", "$marker\ntail", "head\n$marker",
                "head\n$marker\n$marker\ntail")) {
            assertThrows(CompactionArchiveRestoreException::class.java) {
                AgentCompactionArchiveIntegrity.toolReference(tool(content))
            }
        }
        assertNull(AgentCompactionArchiveIntegrity.toolReference(tool("see context-checkpoint:$id")))
        assertNull(AgentCompactionArchiveIntegrity.toolReference(AgentModelClient.ConversationMessage("user", marker)))
    }

    @Test fun growingInputCannotReadBeyondLimitPlusOneOrReturnPartialData() {
        var readBytes = 0
        val input = object : InputStream() {
            override fun read(): Int { readBytes++; return 65 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readBytes += length
                buffer.fill(65.toByte(), offset, offset + length)
                return length
            }
        }
        assertThrows(CompactionArchiveRestoreException::class.java) {
            AgentCompactionArchiveIntegrity.boundedBytes(input, 7)
        }
        assertEquals(8, readBytes)
        assertArrayEquals("1234567".toByteArray(),
            AgentCompactionArchiveIntegrity.boundedBytes(ByteArrayInputStream("1234567".toByteArray()), 7))
    }

    @Test fun verifiedSnapshotIsTheOnlyReturnedContentAndBudgetIsEnforced() {
        val file = temporary.newFile("archive.json")
        val checksum = temporary.newFile("archive.sha256")
        val original = "[verified original]".toByteArray()
        file.writeBytes(original)
        checksum.writeText(hash(original))
        val snapshot = AgentCompactionArchiveIntegrity.verifiedBytes(file, checksum, original.size.toLong())
        file.writeText("changed after verification")
        assertArrayEquals(original, snapshot)
        assertThrows(CompactionArchiveRestoreException::class.java) {
            AgentCompactionArchiveIntegrity.verifiedBytes(file, checksum, 100)
        }
        file.writeBytes(original)
        assertThrows(CompactionArchiveRestoreException::class.java) {
            AgentCompactionArchiveIntegrity.verifiedBytes(file, checksum, original.size - 1L)
        }
        checksum.writeText(hash(original) + "unbounded suffix")
        assertThrows(CompactionArchiveRestoreException::class.java) {
            AgentCompactionArchiveIntegrity.verifiedBytes(file, checksum, 100)
        }
    }

    @Test fun cancelledReadPropagatesInterruptionWithoutPartialResult() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) {
                AgentCompactionArchiveIntegrity.boundedBytes(ByteArrayInputStream(byteArrayOf(1)), 1)
            }
        } finally { Thread.interrupted() }
    }

    @Test fun symbolicArchiveIsNeverOpenedAsAnOrdinaryFile() {
        val original = temporary.newFile("original")
        original.writeText("secret")
        val checksum = temporary.newFile("checksum")
        checksum.writeText(hash(original.readBytes()))
        val link = File(temporary.root, "linked")
        java.nio.file.Files.createSymbolicLink(link.toPath(), original.toPath())
        assertThrows(CompactionArchiveRestoreException::class.java) {
            AgentCompactionArchiveIntegrity.verifiedBytes(link, checksum, 100)
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
