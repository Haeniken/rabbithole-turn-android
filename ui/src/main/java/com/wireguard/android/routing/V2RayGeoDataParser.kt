/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.routing

import com.wireguard.config.InetNetwork
import java.io.IOException
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Minimal, strict reader for the V2Ray GeoIPList and GeoSiteList protobuf formats. */
object V2RayGeoDataParser {
    enum class DomainType(val wireValue: Long) {
        PLAIN(0),
        REGEX(1),
        DOMAIN(2),
        FULL(3),
    }

    data class DomainRule(val type: DomainType, val value: String)

    fun parseIpCategory(bytes: ByteArray, category: String): List<InetNetwork> {
        for (entry in rootEntries(bytes)) {
            if (!readCode(entry).equals(category, ignoreCase = true)) continue
            val result = mutableListOf<InetNetwork>()
            val reader = ProtoReader(entry)
            while (reader.hasRemaining()) {
                val tag = reader.readTag()
                if (tag.number == 2 && tag.wireType == WIRE_LENGTH_DELIMITED) {
                    result += parseCidr(reader.readBytes())
                } else {
                    reader.skip(tag.wireType)
                }
            }
            if (result.isEmpty()) throw IOException("GeoIP category $category is empty")
            return result.distinct()
        }
        throw IOException("GeoIP category $category is missing")
    }

    fun parseDomainCategory(bytes: ByteArray, category: String): List<DomainRule> {
        for (entry in rootEntries(bytes)) {
            if (!readCode(entry).equals(category, ignoreCase = true)) continue
            val result = mutableListOf<DomainRule>()
            val reader = ProtoReader(entry)
            while (reader.hasRemaining()) {
                val tag = reader.readTag()
                if (tag.number == 2 && tag.wireType == WIRE_LENGTH_DELIMITED) {
                    result += parseDomain(reader.readBytes())
                } else {
                    reader.skip(tag.wireType)
                }
            }
            if (result.isEmpty()) throw IOException("GeoSite category $category is empty")
            return result.distinct()
        }
        throw IOException("GeoSite category $category is missing")
    }

    private fun rootEntries(bytes: ByteArray): Sequence<ByteArray> = sequence {
        val reader = ProtoReader(bytes)
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag.number == 1 && tag.wireType == WIRE_LENGTH_DELIMITED) {
                yield(reader.readBytes())
            } else {
                reader.skip(tag.wireType)
            }
        }
    }

    private fun readCode(entry: ByteArray): String {
        val reader = ProtoReader(entry)
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag.number == 1 && tag.wireType == WIRE_LENGTH_DELIMITED) {
                return String(reader.readBytes(), StandardCharsets.UTF_8)
            }
            reader.skip(tag.wireType)
        }
        return ""
    }

    private fun parseCidr(message: ByteArray): InetNetwork {
        val reader = ProtoReader(message)
        var address: ByteArray? = null
        var prefix: Int? = null
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            when {
                tag.number == 1 && tag.wireType == WIRE_LENGTH_DELIMITED -> address = reader.readBytes()
                tag.number == 2 && tag.wireType == WIRE_VARINT -> prefix = reader.readVarint().toInt()
                else -> reader.skip(tag.wireType)
            }
        }
        val rawAddress = address ?: throw IOException("GeoIP CIDR has no address")
        if (rawAddress.size != 4 && rawAddress.size != 16) throw IOException("GeoIP CIDR has an invalid address length")
        val mask = prefix ?: throw IOException("GeoIP CIDR has no prefix")
        if (mask !in 0..rawAddress.size * 8) throw IOException("GeoIP CIDR has an invalid prefix")
        return try {
            InetNetwork.parse("${InetAddress.getByAddress(rawAddress).hostAddress}/$mask")
        } catch (e: Throwable) {
            throw IOException("GeoIP CIDR is invalid", e)
        }
    }

    private fun parseDomain(message: ByteArray): DomainRule {
        val reader = ProtoReader(message)
        var type = DomainType.PLAIN
        var value: String? = null
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            when {
                tag.number == 1 && tag.wireType == WIRE_VARINT -> {
                    val rawType = reader.readVarint()
                    type = DomainType.entries.firstOrNull { it.wireValue == rawType }
                        ?: throw IOException("GeoSite rule has an unsupported type")
                }
                tag.number == 2 && tag.wireType == WIRE_LENGTH_DELIMITED -> {
                    value = String(reader.readBytes(), StandardCharsets.UTF_8).trim().lowercase(Locale.ROOT)
                }
                else -> reader.skip(tag.wireType)
            }
        }
        val ruleValue = value?.takeIf { it.isNotEmpty() }
            ?: throw IOException("GeoSite rule has no value")
        return DomainRule(type, ruleValue)
    }

    private data class Tag(val number: Int, val wireType: Int)

    private class ProtoReader(private val bytes: ByteArray) {
        private var position = 0

        fun hasRemaining() = position < bytes.size

        fun readTag(): Tag {
            val raw = readVarint()
            val number = (raw ushr 3).toInt()
            val wireType = (raw and 7).toInt()
            if (number <= 0) throw IOException("Invalid protobuf field number")
            return Tag(number, wireType)
        }

        fun readVarint(): Long {
            var result = 0L
            for (shift in 0..63 step 7) {
                if (position >= bytes.size) throw IOException("Truncated protobuf varint")
                val value = bytes[position++].toInt() and 0xff
                result = result or ((value and 0x7f).toLong() shl shift)
                if (value and 0x80 == 0) return result
            }
            throw IOException("Protobuf varint is too long")
        }

        fun readBytes(): ByteArray {
            val lengthLong = readVarint()
            if (lengthLong > Int.MAX_VALUE) throw IOException("Invalid protobuf field length")
            val length = lengthLong.toInt()
            if (length > bytes.size - position) throw IOException("Truncated protobuf field")
            return bytes.copyOfRange(position, position + length).also { position += length }
        }

        fun skip(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED_64 -> advance(8)
                WIRE_LENGTH_DELIMITED -> advance(readLength())
                WIRE_FIXED_32 -> advance(4)
                else -> throw IOException("Unsupported protobuf wire type $wireType")
            }
        }

        private fun readLength(): Int {
            val length = readVarint()
            if (length > Int.MAX_VALUE) throw IOException("Invalid protobuf field length")
            return length.toInt()
        }

        private fun advance(length: Int) {
            if (length < 0 || length > bytes.size - position) throw IOException("Truncated protobuf field")
            position += length
        }
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED_64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED_32 = 5
}
