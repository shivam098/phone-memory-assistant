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
import javax.crypto.spec.SecretKeySpec

internal class SealedRecords(val dir: File, val id: UUID, private val raw: ByteArray) : AutoCloseable {
    private var closed = false
    private fun key(): SecretKey {
        check(!closed)
        return SecretKeySpec(raw, "AES")
    }

    fun write(name: String, replace: Boolean = false, encode: DataOutputStream.() -> Unit) {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use(encode)
        val bytes = out.toByteArray()
        try { seal(File(dir, name), id, key(), bytes, replace) } finally { bytes.fill(0) }
    }
    fun <T> read(name: String, decode: DataInputStream.() -> T): T {
        val bytes = unseal(File(dir, name), id, key())
        try { return DataInputStream(bytes.inputStream()).use(decode) } finally { bytes.fill(0) }
    }
    // Wrapped one-time content key and ciphertext share one atomic record and one deletion.
    fun writeChunk(name: String, encode: DataOutputStream.() -> Unit) {
        val rawKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use(encode)
        val plain = out.toByteArray()
        val file = File(dir, name)
        try {
            val wrap = Cipher.getInstance("AES/GCM/NoPadding")
            wrap.init(Cipher.ENCRYPT_MODE, key()); wrap.updateAAD(aad(id, file))
            val wrapped = wrap.doFinal(rawKey)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(rawKey, "AES")); cipher.updateAAD(wrapped)
            check(wrap.iv.size == 12 && wrapped.size == 48 && cipher.iv.size == 12)
            atomicWrite(file, false) { it.write(wrap.iv); it.write(wrapped); it.write(cipher.iv); it.write(cipher.doFinal(plain)) }
        } finally { rawKey.fill(0); plain.fill(0) }
    }
    fun <T> readChunk(name: String, decode: DataInputStream.() -> T): T {
        val file = File(dir, name)
        if (file.length() !in 88L..(MAX_ENCODED + 160L)) throw IOException("Invalid pending audio")
        val bytes = file.readBytes()
        val wrap = Cipher.getInstance("AES/GCM/NoPadding")
        wrap.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12)); wrap.updateAAD(aad(id, file))
        val rawKey = wrap.doFinal(bytes, 12, 48)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(rawKey, "AES"), GCMParameterSpec(128, bytes, 60, 12))
            cipher.updateAAD(bytes, 12, 48)
            val plain = cipher.doFinal(bytes, 72, bytes.size - 72)
            try { return DataInputStream(plain.inputStream()).use(decode) } finally { plain.fill(0) }
        } finally { rawKey.fill(0) }
    }
    override fun close() { closed = true; raw.fill(0) }
}

private fun aad(id: UUID, file: File) = "phone-memory:v2:$id:${file.name}".toByteArray()
internal fun seal(file: File, id: UUID, key: SecretKey, plain: ByteArray, replace: Boolean = false) {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    check(cipher.iv.size == 12)
    cipher.updateAAD(aad(id, file))
    atomicWrite(file, replace) { it.write(cipher.iv); it.write(cipher.doFinal(plain)) }
}
internal fun atomicWrite(file: File, replace: Boolean, write: (FileOutputStream) -> Unit) {
    if (!replace && file.exists()) throw IOException("Record already exists")
    val part = File.createTempFile("write-", ".part", file.parentFile)
    try {
        FileOutputStream(part).use { write(it); it.fd.sync() }
        Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(part.toPath()) }
}
internal fun unseal(file: File, id: UUID, key: SecretKey): ByteArray {
    val limit = if (file.extension == "rev") 16L * 1024 * 1024 else 4L * 1024 * 1024
    if (file.length() !in 28L..limit) throw IOException("Invalid encrypted record")
    val b = file.readBytes()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b, 0, 12))
    cipher.updateAAD(aad(id, file))
    return cipher.doFinal(b, 12, b.size - 12)
}

internal fun SealedRecords.writeSegment(revision: Long, s: Segment) = write("$revision.rev") {
    writeUTF(s.id.toString()); writeLong(s.order); writeUTF(s.provenance.name); writeUTF(s.producer)
    writeLong(s.startMs ?: -1); writeLong(s.endMs ?: -1)
    write(s.text.toByteArray())
}
internal fun SealedRecords.writeBatch(revision: Long, passages: List<NightPassage>) = write("$revision.rev") {
    writeUTF("sealed-batch-v1"); writeInt(passages.size)
    for (p in passages) {
        val bytes = p.encode()
        try { writeInt(bytes.size); write(bytes) } finally { bytes.fill(0) }
    }
}
internal fun SealedRecords.segments(revision: Long): List<Segment> = read("$revision.rev") {
    val tag = readUTF()
    if (tag == "sealed-batch-v1") {
        val count = readInt()
        require(count in 1..QUEUE_CHUNKS)
        return@read List(count) {
            val n = readInt()
            require(n in 1..65536 && n <= available())
            val bytes = ByteArray(n).also(::readFully)
            try { NightPassage.decode(this@segments.id, bytes).segment } finally { bytes.fill(0) }
        }.also { require(available() == 0) }
    }
    val id = UUID.fromString(tag); val order = readLong()
    val kind = Provenance.valueOf(readUTF()); val producer = readUTF()
    val start = readLong().takeIf { it >= 0 }; val end = readLong().takeIf { it >= 0 }
    listOf(Segment(id, order, readBytes().toString(Charsets.UTF_8), kind, producer, start, end))
}
internal fun SealedRecords.saveState(c: Coverage, name: String = "capture.state") = write(name, replace = true) {
    writeUTF(c.phase.name); writeLong(c.sample); writeLong(c.lastAt); writeLong(c.sequence)
    writeInt(c.boundaries.size)
    c.boundaries.forEach {
        writeLong(it.at); writeLong(it.sample); writeUTF(it.reason.name); writeLong(it.resumedAt ?: -1)
    }
    writeLong(c.speechSamples); writeInt(c.startBattery); writeInt(c.lastBattery)
    writeLong(c.startCharge); writeLong(c.lastCharge); writeBoolean(c.baseline)
}
internal fun SealedRecords.state(name: String = "capture.state") = read(name) {
    val phase = CapturePhase.valueOf(readUTF())
    val sample = readLong(); val time = readLong(); val seq = readLong()
    val count = readInt()
    require(count in 0..100_000)
    Coverage(phase, sample, time, seq, List(count) {
        Boundary(readLong(), readLong(), EndReason.valueOf(readUTF()), readLong().takeIf { it >= 0 })
    }, readLong(), readInt(), readInt(), readLong(), readLong(), readBoolean())
}
