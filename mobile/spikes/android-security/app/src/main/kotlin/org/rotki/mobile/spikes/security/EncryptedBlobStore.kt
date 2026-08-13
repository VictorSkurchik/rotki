package org.rotki.mobile.spikes.security

import android.content.Context
import android.util.AtomicFile
import java.io.FileNotFoundException
import java.io.IOException

class EncryptedBlobStore(context: Context) {
    private val file = AtomicFile(context.noBackupFilesDir.resolve(FILE_NAME))

    val exists: Boolean
        get() = try {
            file.openRead().use { true }
        } catch (_: FileNotFoundException) {
            false
        }

    @Throws(IOException::class)
    fun write(blob: EncryptedBlob) {
        val encoded = EncryptedBlobCodec.encode(blob)
        val output = file.startWrite()
        try {
            output.write(encoded)
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    @Throws(IOException::class)
    fun read(): EncryptedBlob? {
        val encoded = try {
            file.openRead().use { input ->
                val maximumBytes = MAX_FILE_BYTES.toInt()
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    require(output.size() + count <= maximumBytes) {
                        "Encrypted envelope file is too large"
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (_: FileNotFoundException) {
            return null
        }
        return EncryptedBlobCodec.decode(encoded)
    }

    fun delete() {
        file.delete()
    }

    internal fun pathForTest() = file.baseFile

    internal fun leaveInterruptedWriteForTest(blob: EncryptedBlob) {
        val output = file.startWrite()
        output.write(EncryptedBlobCodec.encode(blob))
        output.flush()
        output.fd.sync()
        output.close()
    }

    private companion object {
        const val FILE_NAME = "p03-snapshot.envelope"
        const val MAX_FILE_BYTES = (1024 * 1024 + 64).toLong()
    }
}
