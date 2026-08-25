/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import java.io.IOException
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object EncryptedFileCodec {
    private val MAGIC = byteArrayOf(0x52, 0x48, 0x53, 0x45) // RHSE
    private const val VERSION: Byte = 1
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    fun isEncrypted(data: ByteArray): Boolean =
        data.size >= MAGIC.size && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun encrypt(key: SecretKey, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Android Keystore keys created with randomizedEncryptionRequired=true reject a
        // caller-supplied IV. Let the crypto provider generate it, then persist that IV in the
        // authenticated envelope for decryption.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size in 12..32) { "Invalid generated GCM IV length: ${iv.size}" }
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(MAGIC.size + 2 + iv.size + ciphertext.size)
            .put(MAGIC)
            .put(VERSION)
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
    }

    @Throws(IOException::class)
    fun decrypt(key: SecretKey, aad: ByteArray, envelope: ByteArray): ByteArray {
        if (!isEncrypted(envelope) || envelope.size < MAGIC.size + 2 + IV_SIZE + 16)
            throw IOException("Invalid encrypted file envelope")
        val buffer = ByteBuffer.wrap(envelope)
        val magic = ByteArray(MAGIC.size)
        buffer.get(magic)
        val version = buffer.get()
        if (version != VERSION) throw IOException("Unsupported encrypted file version: $version")
        val ivLength = buffer.get().toInt() and 0xff
        if (ivLength !in 12..32 || buffer.remaining() < ivLength + 16)
            throw IOException("Invalid encrypted file IV")
        val iv = ByteArray(ivLength).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw IOException("Encrypted file authentication failed", e)
        } catch (e: Exception) {
            throw IOException("Unable to decrypt protected application data", e)
        }
    }
}
