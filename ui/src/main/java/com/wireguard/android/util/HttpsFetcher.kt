/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import com.wireguard.android.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/** Small HTTPS client with response-size and redirect limits. */
object HttpsFetcher {
    data class Response(
        val status: Int,
        val body: ByteArray,
        val etag: String?,
        val lastModified: String?,
        val contentDisposition: String?,
        val contentType: String?,
    )

    suspend fun get(
        url: String,
        maxBytes: Int,
        etag: String? = null,
        lastModified: String? = null,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        userAgent: String = Application.USER_AGENT,
    ): Response = withContext(Dispatchers.IO) {
        var current = validateUrl(url)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = current.toURL().openConnection() as? HttpsURLConnection
                ?: throw IOException("Only HTTPS URLs are supported")
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.requestMethod = "GET"
                connection.setRequestProperty(
                    "Accept",
                    "application/vnd.rabbithole.turn-bundle+json; version=1, text/plain, application/wireguard-profile, application/octet-stream",
                )
                connection.setRequestProperty("User-Agent", userAgent)
                if (!etag.isNullOrBlank()) connection.setRequestProperty("If-None-Match", etag)
                if (!lastModified.isNullOrBlank()) connection.setRequestProperty("If-Modified-Since", lastModified)

                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    if (redirectCount == MAX_REDIRECTS) throw IOException("Too many HTTPS redirects")
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("HTTPS redirect has no Location header")
                    current = validateUrl(current.resolve(location).toString())
                    return@repeat
                }

                val body = if (status == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > maxBytes) throw IOException("HTTPS response is too large")
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                } else {
                    ByteArray(0)
                }
                return@withContext Response(
                    status,
                    body,
                    connection.getHeaderField("ETag"),
                    connection.getHeaderField("Last-Modified"),
                    connection.getHeaderField("Content-Disposition"),
                    connection.contentType,
                )
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Unable to complete HTTPS request")
    }

    private fun validateUrl(url: String): URI {
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            throw IOException("Invalid HTTPS URL", e)
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.userInfo != null)
            throw IOException("A valid HTTPS URL without embedded credentials is required")
        return uri
    }

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    private const val MAX_REDIRECTS = 4
    private const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
    private const val DEFAULT_READ_TIMEOUT_MS = 30_000
}
