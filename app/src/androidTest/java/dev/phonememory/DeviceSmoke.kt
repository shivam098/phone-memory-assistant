package dev.phonememory

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceSmoke {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun noNetworkOrBackupAndKeystoreRoundTrip() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.packageManager.checkPermission(
            android.Manifest.permission.INTERNET, context.packageName))
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        val alias = "test-${UUID.randomUUID()}"
        val dir = File(context.noBackupFilesDir, alias)
        val key = VaultKey(alias)
        try {
            val source = Vault(dir, key::get).add(Origin.NOTE, "बैठक at noon")
            assertNull(key.get(false).encoded)
            val reopened = Vault(dir, key::get)
            assertEquals(source.id, recall(reopened.load(), "बैठक").single().source)
            reopened.forget(source.id)
            assertTrue(reopened.load().isEmpty())
        } finally {
            dir.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }

    }

    @Test fun installedTextModelCanSelectGroundedEvidence() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("modelSmoke") == "true")
        val source = Source(UUID.randomUUID(), 0, Origin.NOTE,
            "The box is blue.\nकल की meeting शुक्रवार को है। Bring the notebook.")
        val result = LocalInference(context).highlight(source)
        assertNotNull("The engine did not return a valid passage number", result)
        assertTrue(candidates(source).contains(result))
    }

    @Test fun bundledSileroLoadsAndRejectsAnEmptyWindow() {
        SileroGate(context).use { assertFalse(it.speech(ShortArray(512), 512)) }
    }
}
