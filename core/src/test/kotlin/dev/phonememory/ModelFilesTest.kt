package dev.phonememory

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class ModelFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun fixture() = ModelFiles(File(tmp.root, "pinned"), listOf(
        ModelFile("encoder", 2, sha("ab")), ModelFile("decoder", 3, sha("cde"))
    ))

    @Test fun onlyACompleteVerifiedSetIsPublishedInAnyOrder() {
        val model = fixture()
        model.install(listOf({ "cde".byteInputStream() }, { "ab".byteInputStream() })) {}
        assertTrue(model.installed())
        assertEquals("ab", File(model.directory, "encoder").readText())
        model.uninstall()
        assertFalse(model.installed())
    }

    @Test fun wrongHashDuplicateLengthAndInterruptedInstallsLeaveNothingUsable() {
        val model = fixture()
        for (wrong in listOf("cdx", "ab", "abcd")) {
            assertThrows(LocalProblem::class.java) {
                model.install(listOf({ "ab".byteInputStream() }, { wrong.byteInputStream() })) {}
            }
            assertTrue(tmp.root.listFiles()!!.isEmpty())
        }
        var interrupted = false
        assertThrows(CancellationException::class.java) {
            model.install(listOf({ "ab".byteInputStream() }, { interrupted = true; "cde".byteInputStream() })) {
                if (interrupted) throw CancellationException()
            }
        }
        assertTrue(tmp.root.listFiles()!!.isEmpty())
    }
}
