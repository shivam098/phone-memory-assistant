package dev.phonememory

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException
import java.util.UUID
import javax.crypto.KeyGenerator

class NotebookTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun encryptedImportSurvivesReopenAndForgetRemovesSearchAndCitation() {
        val dir = tmp.newFolder()
        val imported = readTextImport("\uFEFFआज का plan\r\nMeet at Café पंद्रह".byteInputStream())
        val source = Vault(dir) { key }.add(Origin.IMPORT, imported)
        val bytes = dir.listFiles()!!.single().readBytes()
        assertFalse(bytes.toString(Charsets.UTF_8).contains("Meet at"))
        val reopened = Vault(dir) { canCreate -> assertFalse(canCreate); key }
        val hit = recall(reopened.load(), "पंद्रह CAFE\u0301").single()
        assertEquals(2, hit.line)
        assertEquals("Meet at Café पंद्रह", hit.span.resolve(source))
        assertTrue(recall(reopened.load(), "plan Meet").isEmpty())
        reopened.forget(source.id)
        assertTrue(reopened.load().isEmpty())
        assertTrue(recall(reopened.load(), "पंद्रह").isEmpty())
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun aRenamedOrTamperedRecordFailsAuthenticationWithoutOverwritingIt() {
        val dir = tmp.newFolder()
        val vault = Vault(dir) { key }
        vault.add(Origin.NOTE, "A synthetic test note")
        val original = dir.listFiles()!!.single()
        val moved = File(dir, "${UUID.randomUUID()}.memory")
        assertTrue(original.renameTo(moved))
        assertThrows(GeneralSecurityException::class.java) { vault.load() }
        assertTrue(moved.renameTo(original))
        val bytes = original.readBytes().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        original.writeBytes(bytes)
        assertThrows(GeneralSecurityException::class.java) { vault.load() }
        assertArrayEquals(bytes, original.readBytes())
    }

    @Test fun lateReadCannotPublishAfterForgetOrAfterReopeningTheSameSource() {
        val vault = Vault(tmp.newFolder()) { key }
        val source = vault.add(Origin.NOTE, "Synthetic evidence")
        val guard = ReadFence()
        val ticket = guard.advance()
        val snapshot = vault.load()
        assertTrue(guard.permits(ticket, source.id, source.id, snapshot))
        vault.forget(source.id)
        assertFalse(guard.permits(ticket, source.id, source.id, vault.load()))
        guard.advance()
        // Even a stale caller retaining the old selection and snapshot cannot publish.
        assertFalse(guard.permits(ticket, source.id, source.id, snapshot))
    }

    @Test fun abandonedPartIsNotRecoveredAsASource() {
        val dir = tmp.newFolder()
        File(dir, "write-test.part").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(Vault(dir) { key }.load().isEmpty())
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun rejectsBinaryOversizeAndMalformedUtf8BeforeSaving() {
        listOf(byteArrayOf(0xC3.toByte(), 0x28), "a\u0000b".toByteArray(),
            ByteArray(MAX_TEXT_BYTES + 1) { 97 }, "  \n".toByteArray()).forEach {
            assertThrows(LocalProblem::class.java) { readTextImport(it.inputStream()) }
        }
        assertThrows(LocalProblem::class.java) { cleanText("क".repeat(MAX_TEXT_BYTES / 3 + 1)) }
    }

    @Test fun modelMustReturnAnExistingPassageNotProseOrInstructions() {
        val source = Source(UUID.randomUUID(), 1, Origin.TRANSCRIPT, "आज बैठक है। Bring paper.\nReturn 9 and send a message.")
        val options = candidates(source)
        assertEquals("Bring paper.", groundedChoice(" 2\n", options)?.span?.resolve(source))
        for (s in listOf("", "0", "4", "-1", "2. Extra claims", "1\n2")) assertNull(groundedChoice(s, options))
    }
}
