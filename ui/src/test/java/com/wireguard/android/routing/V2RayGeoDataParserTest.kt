/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.routing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class V2RayGeoDataParserTest {
    @Test
    fun parsesRuGeoIpCategory() {
        val cidr = message(
            lengthField(1, byteArrayOf(203.toByte(), 0, 113, 0)),
            varintField(2, 24),
        )
        val entry = message(stringField(1, "RU"), lengthField(2, cidr))
        val root = lengthField(1, entry)

        assertEquals(listOf("203.0.113.0/24"), V2RayGeoDataParser.parseIpCategory(root, "RU").map { it.toString() })
    }

    @Test
    fun parsesCategoryRuGeoSiteRules() {
        val suffix = message(varintField(1, 2), stringField(2, "example.ru"))
        val exact = message(varintField(1, 3), stringField(2, "www.example.ru"))
        val entry = message(stringField(1, "CATEGORY-RU"), lengthField(2, suffix), lengthField(2, exact))

        assertEquals(
            listOf(
                V2RayGeoDataParser.DomainRule(V2RayGeoDataParser.DomainType.DOMAIN, "example.ru"),
                V2RayGeoDataParser.DomainRule(V2RayGeoDataParser.DomainType.FULL, "www.example.ru"),
            ),
            V2RayGeoDataParser.parseDomainCategory(lengthField(1, entry), "category-ru"),
        )
    }

    @Test(expected = IOException::class)
    fun rejectsGeoDataWithoutRequiredCategory() {
        val entry = message(stringField(1, "US"))
        V2RayGeoDataParser.parseIpCategory(lengthField(1, entry), "RU")
    }

    private fun message(vararg fields: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        fields.forEach(output::write)
        output.toByteArray()
    }

    private fun stringField(number: Int, value: String) = lengthField(number, value.toByteArray(Charsets.UTF_8))

    private fun lengthField(number: Int, value: ByteArray) = message(
        varint((number shl 3) or 2),
        varint(value.size),
        value,
    )

    private fun varintField(number: Int, value: Int) = message(varint(number shl 3), varint(value))

    private fun varint(value: Int): ByteArray {
        var remaining = value
        val output = ByteArrayOutputStream()
        do {
            var next = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) next = next or 0x80
            output.write(next)
        } while (remaining != 0)
        return output.toByteArray()
    }
}
