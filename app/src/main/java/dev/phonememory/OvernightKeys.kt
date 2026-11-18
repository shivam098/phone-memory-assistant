package dev.phonememory

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

internal fun syncDirectory(dir: File) {
    try {
        if (!dir.isDirectory) throw IOException("Not a storage directory")
        val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    } catch (e: android.system.ErrnoException) { throw IOException("Could not commit local storage", e) }
}

internal class OvernightKeys(private val ctx: Context, private val alias: String = "phone-memory-overnight-rsa-v1") {
    private val marker = AtomicFile(File(ctx.noBackupFilesDir, "$alias.public"))
    private val lock get() = ctx.getSystemService(KeyguardManager::class.java)
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val envelope = OvernightEnvelope(::recipient, ::privateKey)
    private fun privateKey(): PrivateKey {
        if (lock.isDeviceLocked) throw LocalProblem("Unlock to read sealed passages.")
        return store().getKey(alias, null) as? PrivateKey ?: throw LocalProblem("The sealed-passages key is unavailable.")
    }

    @Synchronized fun prepare(allowNewKey: Boolean) {
        if (lock.isDeviceLocked || !lock.isDeviceSecure) throw LocalProblem("Set a secure screen lock and unlock to prepare overnight processing.")
        val keys = store()
        if (!keys.containsAlias(alias)) {
            if (!allowNewKey || marker.baseFile.exists()) throw LocalProblem("The sealed-passages key is missing. No key was replaced.")
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            gen.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setDigests(KeyProperties.DIGEST_SHA256).setUnlockedDeviceRequired(true).build())
            gen.generateKeyPair()
        }
        val encoded = store().getCertificate(alias).publicKey.encoded
        if (marker.baseFile.exists() && !marker.readFully().contentEquals(encoded))
            throw LocalProblem("Sealing key changed. Audio was kept.")
        val public = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encoded))
        val test = OvernightEnvelope({ public }, ::privateKey)
        val id = java.util.UUID.randomUUID()
        val probe = byteArrayOf(1)
        if (!test.open(id, 0, test.seal(id, 0, probe)).contentEquals(probe))
            throw LocalProblem("This device could not prepare overnight processing.")
        if (!marker.baseFile.exists()) {
            val stream = marker.startWrite()
            try { stream.write(encoded); marker.finishWrite(stream) }
            catch (e: IOException) { marker.failWrite(stream); throw e }
            syncDirectory(ctx.noBackupFilesDir)
        }
    }

    private fun recipient(): PublicKey {
        val cert = store().getCertificate(alias) ?: throw LocalProblem("Open the app unlocked to prepare overnight processing.")
        val pinned = marker.readFully()
        if (!pinned.contentEquals(cert.publicKey.encoded)) throw LocalProblem("Sealing key changed. Audio was kept.")
        // X.509 public material provides encryption only; device CE storage is still required.
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(pinned))
    }
}
