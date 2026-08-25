/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** App-private authenticated encryption with transparent legacy migration. */
class SecureFileStore(context: Context) {
    data class ReadResult(val bytes: ByteArray, val encrypted: Boolean)

    private val applicationContext = context.applicationContext
    private val lock = Any()

    @Throws(IOException::class)
    fun read(file: File): ReadResult = synchronized(lock) {
        val stored = file.readBytes()
        if (!EncryptedFileCodec.isEncrypted(stored)) return@synchronized ReadResult(stored, false)
        ReadResult(EncryptedFileCodec.decrypt(getOrCreateKey(), aad(file), stored), true)
    }

    @Throws(IOException::class)
    fun migrateIfNeeded(file: File, result: ReadResult) {
        if (!result.encrypted) write(file, result.bytes)
    }

    @Throws(IOException::class)
    fun write(file: File, plaintext: ByteArray) = synchronized(lock) {
        val parent = file.parentFile ?: applicationContext.filesDir
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Unable to create protected storage directory")
        val envelope = try {
            EncryptedFileCodec.encrypt(getOrCreateKey(), aad(file), plaintext)
        } catch (e: Exception) {
            throw IOException("Unable to encrypt protected application data", e)
        }
        val temporary = File.createTempFile(".${file.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(envelope)
                output.fd.sync()
            }
            Os.chmod(temporary.path, 0b110000000) // 0600
            Os.rename(temporary.path, file.path)
        } catch (e: Exception) {
            throw IOException("Unable to atomically save protected application data", e)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun aad(file: File): ByteArray = file.name.toByteArray(Charsets.UTF_8)

    @Throws(IOException::class)
    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generator.generateKey()
        } catch (e: Exception) {
            throw IOException("Android Keystore is unavailable", e)
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "rabbithole.protected-files.v1"
        val KEY_LOCK = Any()
    }
}
