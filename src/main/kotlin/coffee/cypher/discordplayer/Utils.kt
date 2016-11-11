package coffee.cypher.discordplayer

import java.io.ByteArrayOutputStream
import java.io.InputStream

class Utils {
    private val bufferThreadLocal = ThreadLocal<ByteArray>()

    fun getIOBuffer(): ByteArray {
        var buffer = bufferThreadLocal.get()
        if (buffer == null) {
            buffer = ByteArray(8192)
            bufferThreadLocal.set(buffer)
        }
        return buffer
    }

    fun readToString(`in`: InputStream): String {
        val baos = ByteArrayOutputStream()
        val buffer = getIOBuffer()
        var readSize: Int

        readSize = `in`.read(buffer)
        while (readSize >= 0) {
            baos.write(buffer, 0, readSize)
            readSize = `in`.read(buffer)
        }
        val data = baos.toByteArray()
        val content = String(data)
        return content
    }
}