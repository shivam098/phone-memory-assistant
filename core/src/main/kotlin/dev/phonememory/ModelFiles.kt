package dev.phonememory

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class ModelFile(val name: String, val size: Long, val sha256: String)

/** Receives only the compiled-in file specifications. Provider filenames are never used. */
class ModelFiles(val directory: File, val files: List<ModelFile>) {
    private val staging = File(directory.parentFile, "${directory.name}.incoming")
    fun installed() = files.all { File(directory, it.name).let { f -> f.isFile && f.length() == it.size } }

    fun clearInterruptedInstall() = remove(staging)

    fun install(inputs: List<() -> InputStream>, checkActive: () -> Unit) {
        if (installed()) throw LocalProblem("This model is already installed.")
        if (inputs.size != files.size) throw LocalProblem("Select exactly ${files.size} model file(s) together.")
        clearInterruptedInstall()
        if (!staging.mkdirs()) throw IOException("Cannot stage model")
        try {
            val remaining = files.toMutableList()
            val buf = ByteArray(128 * 1024)
            for (open in inputs) {
                checkActive()
                val tmp = File(staging, "part")
                val digest = MessageDigest.getInstance("SHA-256")
                val limit = remaining.maxOf { it.size }
                var size = 0L
                open().use { input ->
                    tmp.outputStream().buffered().use { out ->
                        while (true) {
                            checkActive()
                            val n = input.read(buf)
                            if (n == -1) break
                            size += n
                            if (size > limit) throw LocalProblem("Not the expected model file (size mismatch).")
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                val spec = remaining.find { it.size == size && it.sha256 == hex }
                    ?: throw LocalProblem("Wrong, duplicate or damaged model file. Nothing was installed.")
                remaining.remove(spec)
                Files.move(tmp.toPath(), File(staging, spec.name).toPath())
            }
            checkActive()
            remove(directory) // Only an incomplete installation can reach here.
            Files.move(staging.toPath(), directory.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            clearInterruptedInstall()
        }
    }

    fun uninstall() = remove(directory)

    private fun remove(dir: File) {
        if (dir.exists() && !dir.deleteRecursively()) throw IOException("Could not remove model files")
    }
}
