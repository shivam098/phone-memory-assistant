package dev.phonememory

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** Separate capabilities: sealing never calls the private-key supplier. */
class OvernightEnvelope(private val recipient: () -> PublicKey, private val unlock: () -> PrivateKey) {
    private val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    private fun identity(id: UUID, seq: Long) = "phone-memory:sealed:v1:$id:$seq".toByteArray()
    fun seal(id: UUID, seq: Long, plain: ByteArray): ByteArray {
        require(plain.size in 1..MAX_PAYLOAD)
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
            rsa.init(Cipher.ENCRYPT_MODE, recipient(), oaep)
            val wrapped = rsa.doFinal(raw)
            val aes = Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(raw, "AES"))
            check(aes.iv.size == 12)
            aes.updateAAD(identity(id, seq)); aes.updateAAD(wrapped)
            val stream = ByteArrayOutputStream()
            DataOutputStream(stream).use { out ->
                out.writeInt(1); out.writeInt(wrapped.size); out.write(wrapped)
                out.write(aes.iv); out.write(aes.doFinal(plain))
            }
            return stream.toByteArray()
        } finally { raw.fill(0) }
    }

    fun open(id: UUID, seq: Long, sealed: ByteArray): ByteArray {
        if (sealed.size !in 292..(MAX_PAYLOAD + 1024)) throw IOException("Invalid sealed result")
        DataInputStream(sealed.inputStream()).use { input ->
            if (input.readInt() != 1) throw IOException("Unsupported sealed result")
            val n = input.readInt()
            if (n !in 256..512) throw IOException("Invalid wrapped key")
            val wrapped = ByteArray(n).also(input::readFully)
            val iv = ByteArray(12).also(input::readFully)
            val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
            rsa.init(Cipher.DECRYPT_MODE, unlock(), oaep)
            val raw = rsa.doFinal(wrapped)
            try {
                require(raw.size == 32)
                val aes = Cipher.getInstance("AES/GCM/NoPadding")
                aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(raw, "AES"), GCMParameterSpec(128, iv))
                aes.updateAAD(identity(id, seq)); aes.updateAAD(wrapped)
                return aes.doFinal(input.readBytes())
            } finally { raw.fill(0) }
        }
    }
    private companion object { const val MAX_PAYLOAD = 64 * 1024 }
}

data class PassagePick(val from: Int, val until: Int, val line: Int, val producer: String) {
    fun resolve(source: Source, segment: Segment): Excerpt? {
        val span = EvidenceSpan(source.id, source.revision, segment.id, from, until)
        return span.resolve(source)?.takeIf { it.isNotBlank() }?.let { Excerpt(span, line, it) }
    }
}

data class MergeResult(val merged: Int = 0, val stale: Int = 0, val sources: Set<UUID> = emptySet())
class RevisionAdvanced : Exception()
internal data class NightPassage(val base: Long, val segment: Segment) {
    fun encode(): ByteArray {
        val buf = ByteArrayOutputStream()
        DataOutputStream(buf).use {
            it.writeLong(base)
            it.writeLong(segment.order); it.writeLong(requireNotNull(segment.startMs)); it.writeLong(requireNotNull(segment.endMs))
            it.writeUTF(segment.producer); it.writeUTF(segment.text)
            val pick = segment.pick
            it.writeBoolean(pick != null)
            if (pick != null) { it.writeInt(pick.from); it.writeInt(pick.until); it.writeInt(pick.line); it.writeUTF(pick.producer) }
        }
        return buf.toByteArray()
    }
    companion object {
        fun decode(source: UUID, bytes: ByteArray): NightPassage = DataInputStream(bytes.inputStream()).use {
            val base = it.readLong(); val seq = it.readLong(); val start = it.readLong(); val end = it.readLong()
            val producer = it.readUTF(); val text = cleanText(it.readUTF())
            val pick = if (it.readBoolean()) PassagePick(it.readInt(), it.readInt(), it.readInt(), it.readUTF()) else null
            require(base >= 0 && seq > 0 && start >= 0 && end >= start && it.read() == -1)
            NightPassage(base, Segment(asrId(source, seq), seq, text, Provenance.LOCAL_ASR, producer, start, end, pick))
        }
    }
}
internal fun asrId(id: UUID, seq: Long) = UUID.nameUUIDFromBytes("$id:$seq".toByteArray())
fun AudioInput.passage(text: String, producer: String) =
    Segment(asrId(item.source, item.sequence), item.sequence, cleanText(text), Provenance.LOCAL_ASR, producer,
        item.start * 1000 / SAMPLE_RATE, item.end * 1000 / SAMPLE_RATE)
