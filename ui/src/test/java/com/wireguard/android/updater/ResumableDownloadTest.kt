package com.wireguard.android.updater

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableDownloadTest {
    @get:Rule val directory = TemporaryFolder()
    private val url = URL("https://example.invalid/release.apk")
    private val data = "verified apk bytes".toByteArray()
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(data)
    private fun file() = File(directory.root, "download.part")
    private class Response(val status: Int, val body: InputStream, val size: Long, val range: String? = null) : HttpURLConnection(URL("https://example.invalid/release.apk")) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = body
        override fun getContentLengthLong() = size
        override fun getHeaderField(name: String?) = if (name == "Content-Range") range else null
    }
    private fun response(code: Int, body: ByteArray, range: String? = null) = Response(code, ByteArrayInputStream(body), body.size.toLong(), range)

    @Test fun interruptedTransferResumesFromSavedOffsetAndVerifiesWholeFile() {
        val partial = file()
        val interrupted = object : InputStream() {
            var count=0
            override fun read(): Int = if(count<5) data[count++].toInt() else throw IOException("link down")
            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                if (count>=5) throw IOException("link down")
                data.copyInto(bytes, off, 0, 5); count=5; return 5
            }
        }
        assertThrows(IOException::class.java) {
            ResumableDownload(connect={Response(200, interrupted, data.size.toLong())}).fetch(url,partial,hash) { _,_-> }
        }
        assertEquals(5,partial.length().toInt())
        val resume=response(206,data.copyOfRange(5,data.size),"bytes 5-${data.size-1}/${data.size}")
        ResumableDownload(connect={resume}).fetch(url,partial,hash) { _,_-> }
        assertEquals("bytes=5-",resume.getRequestProperty("Range"))
        assertArrayEquals(data,partial.readBytes())
    }

    @Test fun ignoredRangeRestartsWithoutAppendingDuplicateBytes() {
        val partial=file().apply{writeBytes(data.copyOfRange(0,5))}
        ResumableDownload(connect={response(200,data)}).fetch(url,partial,hash) { _,_-> }
        assertArrayEquals(data,partial.readBytes())
    }

    @Test fun mismatchedContentRangeCannotOverwritePartialFile() {
        val partial=file().apply{writeBytes(data.copyOfRange(0,5))}
        assertThrows(IOException::class.java) {
            ResumableDownload(connect={response(206,data,"bytes 0-${data.size-1}/${data.size}")}).fetch(url,partial,hash){_,_->}
        }
        assertEquals(5,partial.length().toInt())
    }

    @Test fun truncatedBodyIsPreservedForRetry() {
        val partial=file()
        assertThrows(IOException::class.java) {
            ResumableDownload(connect={Response(200,ByteArrayInputStream(data.copyOfRange(0,5)),data.size.toLong())}).fetch(url,partial,hash){_,_->}
        }
        assertEquals(5,partial.length().toInt())
    }

    @Test fun corruptedApkIsDeletedAndNeverInstalled() {
        val partial=file()
        assertThrows(SecurityException::class.java) {
            ResumableDownload(connect={response(200,"wrong apk".toByteArray())}).fetch(url,partial,hash){_,_->}
        }
        assertFalse(partial.exists())
    }

    @Test fun completeVerifiedFileIsReusedAfterProcessRestartWithoutNetwork() {
        val partial=file().apply{writeBytes(data)}
        ResumableDownload(connect={error("must not connect")}).fetch(url,partial,hash){downloaded,total->assertEquals(downloaded,total)}
    }

    @Test fun rangeNotSatisfiableDropsBadPartialAndCanRetryFromZero() {
        val partial=file().apply{writeBytes(data.copyOfRange(0,5))}
        assertThrows(IOException::class.java) {
            ResumableDownload(connect={response(416,byteArrayOf())}).fetch(url,partial,hash){_,_->}
        }
        assertFalse(partial.exists())
        ResumableDownload(connect={response(200,data)}).fetch(url,partial,hash){_,_->}
        assertArrayEquals(data,partial.readBytes())
    }

    @Test fun oversizedResponseIsRejectedBeforeWriting() {
        val partial=file()
        assertThrows(IOException::class.java) {
            ResumableDownload(connect={Response(200,ByteArrayInputStream(data),ResumableDownload.MAX_BYTES+1)}).fetch(url,partial,hash){_,_->}
        }
        assertFalse(partial.exists())
    }
}
