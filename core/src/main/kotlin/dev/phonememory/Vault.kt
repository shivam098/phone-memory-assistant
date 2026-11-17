package dev.phonememory

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Immutable records, no plaintext journal, search index or saved inference result. */
class Vault(private val dir: File, private val key: (create: Boolean) -> SecretKey) {
    private fun records(): List<File> {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Vault unavailable")
        return (dir.listFiles() ?: throw IOException("Vault unavailable")).filter { it.extension == "memory" }
    }

    @Synchronized
    fun load(): List<Source> {
        val files = records()
        // A killed insert may leave ciphertext here. Never promote it to a memory on recovery.
        dir.listFiles { f -> f.extension == "part" }?.forEach { Files.delete(it.toPath()) }
        if (files.isEmpty()) return emptyList()
        val secret = key(false)
        return files.map { file ->
            val id = UUID.fromString(file.nameWithoutExtension)
            if (file.length() !in 29L..(MAX_TEXT_BYTES + 128L)) throw IOException("Invalid record size")
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, bytes, 0, 12))
            cipher.updateAAD(aad(id))
            DataInputStream(cipher.doFinal(bytes, 12, bytes.size - 12).inputStream()).use {
                Source(id, it.readLong(), Origin.valueOf(it.readUTF()), it.readBytes().toString(Charsets.UTF_8))
            }
        }.sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun add(origin: Origin, value: String): Source {
        val text = cleanText(value)
        val files = records()
        if (files.size >= 100) throw LocalProblem("Notebook full (100 sources). Forget a source before adding one.")
        val source = Source(UUID.randomUUID(), System.currentTimeMillis(), origin, text)
        val clear = ByteArrayOutputStream()
        DataOutputStream(clear).use {
            it.writeLong(source.createdAt)
            it.writeUTF(origin.name)
            it.write(text.toByteArray())
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(files.isEmpty()))
        cipher.updateAAD(aad(source.id))
        check(cipher.iv.size == 12)
        val part = File.createTempFile("write-", ".part", dir)
        try {
            FileOutputStream(part).use {
                it.write(cipher.iv)
                it.write(cipher.doFinal(clear.toByteArray()))
                it.fd.sync()
            }
            Files.move(part.toPath(), file(source.id).toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(part.toPath())
        }
        return source
    }

    @Synchronized
    fun forget(id: UUID) { Files.delete(file(id).toPath()) }

    private fun file(id: UUID) = File(dir, "$id.memory")
    private fun aad(id: UUID) = "phone-memory:v1:$id".toByteArray()
}
