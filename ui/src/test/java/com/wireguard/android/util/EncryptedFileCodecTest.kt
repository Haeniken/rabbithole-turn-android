/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import java.io.IOException
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedFileCodecTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun roundTripAuthenticatesContentAndFileName() {
        val plaintext = "private-key-and-subscription-token".toByteArray()
        val first = EncryptedFileCodec.encrypt(key, "Alice.conf".toByteArray(), plaintext)
        val second = EncryptedFileCodec.encrypt(key, "Alice.conf".toByteArray(), plaintext)

        assertTrue(EncryptedFileCodec.isEncrypted(first))
        assertFalse(first.contentEquals(plaintext))
        assertNotEquals(first.toList(), second.toList())
        assertArrayEquals(plaintext, EncryptedFileCodec.decrypt(key, "Alice.conf".toByteArray(), first))
    }

    @Test(expected = IOException::class)
    fun rejectsTampering() {
        val encrypted = EncryptedFileCodec.encrypt(key, "Alice.conf".toByteArray(), byteArrayOf(1, 2, 3))
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        EncryptedFileCodec.decrypt(key, "Alice.conf".toByteArray(), encrypted)
    }

    @Test(expected = IOException::class)
    fun rejectsMovingCiphertextToAnotherProfile() {
        val encrypted = EncryptedFileCodec.encrypt(key, "Alice.conf".toByteArray(), byteArrayOf(1, 2, 3))
        EncryptedFileCodec.decrypt(key, "Dragon.conf".toByteArray(), encrypted)
    }
}
