package dev.phonememory

import android.app.KeyguardManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.util.UUID

/** Three explicit, synthetic-only invocations so that an automatic unlock cannot produce a pass. */
@RunWith(AndroidJUnit4::class)
class OvernightDeviceProbe {
    @Test fun publicWhileLockedPrivateOnlyAfterUnlock() {
        val phase = InstrumentationRegistry.getArguments().getString("overnightKeyPhase")
        assumeTrue(phase != null)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val lock = ctx.getSystemService(KeyguardManager::class.java)
        assertTrue("Use a secure screen lock on a dedicated test device", lock.isDeviceSecure)
        val alias = "phone-memory-test-overnight"
        val keys = OvernightKeys(ctx, alias)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val blob = File(ctx.noBackupFilesDir, "$alias.fixture")
        val marker = File(ctx.noBackupFilesDir, "$alias.public")
        val id = UUID(0, 7)
        val fixture = "synthetic-only".toByteArray()
        when (phase) {
            "prepare" -> {
                assertFalse(lock.isDeviceLocked)
                keys.prepare(true)
                assertNull((store.getKey(alias, null) as PrivateKey).encoded)
            }
            "locked" -> {
                assertTrue("Relock with the secure keyguard before running this phase", lock.isDeviceLocked)
                val encoded = keys.envelope.seal(id, 1, fixture)
                blob.writeBytes(encoded)
                // Bypass the app guard for this test only: the OS itself must reject private use.
                val raw = OvernightEnvelope({ store.getCertificate(alias).publicKey }, { store.getKey(alias, null) as PrivateKey })
                try { raw.open(id, 1, encoded); fail("Keystore allowed private decrypt while locked") }
                catch (_: GeneralSecurityException) { /* expected Keystore enforcement */ }
                catch (_: ProviderException) { /* some providers surface a wrapped Keystore failure */ }
            }
            "unlock" -> {
                assertFalse(lock.isDeviceLocked)
                try { assertArrayEquals(fixture, keys.envelope.open(id, 1, blob.readBytes())) }
                finally { store.deleteEntry(alias); blob.delete(); marker.delete() }
            }
            else -> fail("Use prepare, locked, then unlock")
        }
    }
}
