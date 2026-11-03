/* SPDX-License-Identifier: Apache-2.0 */
package com.wireguard.android.turn

import android.content.Context
import com.wireguard.android.backend.TurnBackend
import com.wireguard.android.util.SecureFileStore
import java.io.File
import java.io.IOException

/** Ephemeral TURN identities, encrypted by the existing Android Keystore store. */
class TurnCredentialCache(context: Context) : TurnBackend.CredentialCache {
    private val directory = File(context.applicationContext.noBackupFilesDir, "turn-credentials")
    private val store = SecureFileStore(context)

    init {
        // VK identities normally live eight hours. Do not retain abandoned
        // profile caches indefinitely or include them in Android backups.
        val oldest = System.currentTimeMillis() - MAX_FILE_AGE_MILLIS
        directory.listFiles()?.filter { it.isFile && it.lastModified() < oldest }?.forEach { it.delete() }
    }

    @Synchronized
    override fun read(key: String): String {
        val file = fileFor(key)
        if (!file.isFile) return ""
        if (file.length() > MAX_FILE_BYTES) throw IOException("TURN cache is too large")
        val result = store.read(file)
        // Unlike profile migrations, credentials have no legacy plaintext form.
        if (!result.encrypted) {
            file.delete()
            return ""
        }
        return result.bytes.toString(Charsets.UTF_8)
    }

    @Synchronized
    override fun write(key: String, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES - 1024) throw IOException("TURN cache is too large")
        store.write(fileFor(key), bytes)
    }

    private fun fileFor(key: String): File {
        require(KEY_PATTERN.matches(key)) { "Invalid TURN cache key" }
        return File(directory, "$key.cache")
    }

    private companion object {
        val KEY_PATTERN = Regex("[0-9a-f]{64}")
        const val MAX_FILE_BYTES = 256 * 1024L
        const val MAX_FILE_AGE_MILLIS = 24 * 60 * 60 * 1000L
    }
}
