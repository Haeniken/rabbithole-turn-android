/*
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale
import kotlin.math.roundToInt

/** Browser identity shared by native VK authorization and the CAPTCHA WebView. */
@ConsistentCopyVisibility
data class CaptchaBrowserProfile private constructor(
    val userAgent: String,
    val secChUa: String,
    val secChUaMobile: String,
    val secChUaPlatform: String,
    val language: String,
    val languages: List<String>,
    val navigatorPlatform: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val screenAvailWidth: Int,
    val screenAvailHeight: Int,
    val innerWidth: Int,
    val innerHeight: Int,
    val devicePixelRatio: Double,
    val hardwareConcurrency: Int,
    val deviceMemory: Int,
    val adFp: String,
    val browserFp: String,
    val capturedDeviceJson: String,
    val createdAtEpochSeconds: Long,
) {
    fun acceptLanguageHeader(): String = languages.take(3).mapIndexed { index, value ->
        if (index == 0) value else "$value;q=${"%.1f".format(Locale.US, 1.0 - index * 0.1)}"
    }.joinToString(",")

    fun toJson(): String = JSONObject()
        .put("userAgent", userAgent)
        .put("secChUa", secChUa)
        .put("secChUaMobile", secChUaMobile)
        .put("secChUaPlatform", secChUaPlatform)
        .put("language", language)
        .put("languages", JSONArray(languages))
        .put("navigatorPlatform", navigatorPlatform)
        .put("screenWidth", screenWidth)
        .put("screenHeight", screenHeight)
        .put("screenAvailWidth", screenAvailWidth)
        .put("screenAvailHeight", screenAvailHeight)
        .put("innerWidth", innerWidth)
        .put("innerHeight", innerHeight)
        .put("devicePixelRatio", devicePixelRatio)
        .put("hardwareConcurrency", hardwareConcurrency)
        .put("deviceMemory", deviceMemory)
        .put("adFp", adFp)
        .put("browserFp", browserFp)
        .put("capturedDeviceJson", capturedDeviceJson)
        .put("createdAtEpochSeconds", createdAtEpochSeconds)
        .toString()

    fun navigatorOverridesScript(): String {
        val languageJson = JSONObject.quote(language)
        val languagesJson = JSONArray(languages).toString()
        val platformJson = JSONObject.quote(navigatorPlatform)
        return """
            (function() {
                function define(target, name, value) {
                    try {
                        Object.defineProperty(target, name, {
                            configurable: true,
                            get: function() { return value; }
                        });
                    } catch (_) {}
                }
                define(Navigator.prototype, 'language', $languageJson);
                define(Navigator.prototype, 'languages', $languagesJson);
                define(Navigator.prototype, 'platform', $platformJson);
                define(Navigator.prototype, 'hardwareConcurrency', $hardwareConcurrency);
                define(Navigator.prototype, 'deviceMemory', $deviceMemory);
                define(Navigator.prototype, 'webdriver', false);
            })();
        """.trimIndent()
    }

    companion object {
        private const val DEFAULT_CHROME_MAJOR = 146
        private const val PREFERENCES_NAME = "captcha_browser_profile"
        private const val PREFERENCE_PROFILE = "stable_profile_v2"

        @Volatile
        private var cached: Pair<String, CaptchaBrowserProfile>? = null

        fun get(context: Context): CaptchaBrowserProfile {
            val userAgent = TurnUserAgentSettings.resolve(defaultUserAgent(context))
            cached?.takeIf { it.first == userAgent }?.second?.let { return it }
            return synchronized(this) {
                cached?.takeIf { it.first == userAgent }?.second
                    ?: load(context.applicationContext)
                        ?.takeIf { it.userAgent == userAgent }
                        ?.also {
                            persist(context.applicationContext, it)
                            cached = userAgent to it
                        }
                    ?: create(context.applicationContext, userAgent).also {
                        persist(context.applicationContext, it)
                        cached = userAgent to it
                    }
            }
        }

        /** Saves browser-generated values captured during a successful manual session. */
        fun recordCaptured(context: Context, browserFp: String?, deviceJson: String?, adFp: String?) {
            synchronized(this) {
                val current = get(context)
                val normalizedBrowserFp = browserFp.orEmpty().takeIf { it.length in 16..512 }
                val normalizedDevice = deviceJson.orEmpty().takeIf {
                    it.length in 2..16_384 && runCatching { JSONObject(it) }.isSuccess
                }
                val normalizedAdFp = adFp.orEmpty().takeIf { it.length in 16..128 }
                if (normalizedBrowserFp == null && normalizedDevice == null && normalizedAdFp == null) return
                val updated = current.copy(
                    browserFp = normalizedBrowserFp ?: current.browserFp,
                    capturedDeviceJson = normalizedDevice ?: current.capturedDeviceJson,
                    adFp = normalizedAdFp ?: current.adFp,
                )
                if (updated == current) return
                persist(context.applicationContext, updated)
                cached = updated.userAgent to updated
            }
        }

        fun defaultUserAgent(context: Context): String {
            val model = Build.MODEL.replace(Regex("[();]"), " ").trim().ifBlank { "Android" }
            val androidVersion = Build.VERSION.RELEASE.ifBlank { "14" }
            return "Mozilla/5.0 (Linux; Android $androidVersion; $model) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$DEFAULT_CHROME_MAJOR.0.0.0 Mobile Safari/537.36"
        }

        private fun create(context: Context, userAgent: String): CaptchaBrowserProfile {
            val metrics = context.resources.displayMetrics
            val density = metrics.density.takeIf { it > 0f } ?: 1f
            val width = (metrics.widthPixels / density).roundToInt().coerceAtLeast(1)
            val height = (metrics.heightPixels / density).roundToInt().coerceAtLeast(1)
            val locale = Locale.getDefault()
            val language = locale.toLanguageTag().ifBlank { "en-US" }
            val languages = listOf(language, locale.language, "en").filter { it.isNotBlank() }.distinct()
            val chromeMajor = CHROME_VERSION_REGEX.find(userAgent)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: DEFAULT_CHROME_MAJOR
            val navigatorPlatform = if (Build.SUPPORTED_ABIS.firstOrNull()?.contains("x86") == true) {
                "Linux x86_64"
            } else {
                "Linux armv81"
            }

            return CaptchaBrowserProfile(
                userAgent = userAgent,
                secChUa = "\"Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"$chromeMajor\", \"Chromium\";v=\"$chromeMajor\"",
                secChUaMobile = "?1",
                secChUaPlatform = "\"Android\"",
                language = language,
                languages = languages,
                navigatorPlatform = navigatorPlatform,
                screenWidth = width,
                screenHeight = height,
                screenAvailWidth = width,
                screenAvailHeight = height,
                innerWidth = width,
                innerHeight = height,
                devicePixelRatio = density.toDouble(),
                hardwareConcurrency = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                deviceMemory = getDeviceMemoryBucket(context),
                adFp = newAdFp(),
                browserFp = newBrowserFp(),
                capturedDeviceJson = "",
                createdAtEpochSeconds = System.currentTimeMillis() / 1000,
            )
        }

        private fun load(context: Context): CaptchaBrowserProfile? = runCatching {
            val raw = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(PREFERENCE_PROFILE, null) ?: return null
            val json = JSONObject(raw)
            val languagesJson = json.getJSONArray("languages")
            val languages = buildList {
                for (index in 0 until languagesJson.length()) add(languagesJson.getString(index))
            }
            CaptchaBrowserProfile(
                userAgent = json.getString("userAgent"),
                secChUa = json.getString("secChUa"),
                secChUaMobile = json.getString("secChUaMobile"),
                secChUaPlatform = json.getString("secChUaPlatform"),
                language = json.getString("language"),
                languages = languages,
                navigatorPlatform = json.getString("navigatorPlatform"),
                screenWidth = json.getInt("screenWidth"),
                screenHeight = json.getInt("screenHeight"),
                screenAvailWidth = json.getInt("screenAvailWidth"),
                screenAvailHeight = json.getInt("screenAvailHeight"),
                innerWidth = json.getInt("innerWidth"),
                innerHeight = json.getInt("innerHeight"),
                devicePixelRatio = json.getDouble("devicePixelRatio"),
                hardwareConcurrency = json.getInt("hardwareConcurrency"),
                deviceMemory = json.getInt("deviceMemory"),
                adFp = json.optString("adFp").takeIf { it.length >= 16 } ?: newAdFp(),
                browserFp = json.optString("browserFp").takeIf { it.length >= 16 } ?: newBrowserFp(),
                capturedDeviceJson = json.optString("capturedDeviceJson"),
                createdAtEpochSeconds = json.optLong("createdAtEpochSeconds", System.currentTimeMillis() / 1000),
            )
        }.getOrNull()

        private fun persist(context: Context, profile: CaptchaBrowserProfile) {
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREFERENCE_PROFILE, profile.toJson())
                .apply()
        }

        private fun newAdFp(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).take(21)
        }

        private fun newBrowserFp(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
        }

        private fun getDeviceMemoryBucket(context: Context): Int {
            val memoryInfo = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memoryInfo)
            val gibibytes = memoryInfo.totalMem / (1024L * 1024L * 1024L)
            return when {
                gibibytes <= 1 -> 1
                gibibytes <= 2 -> 2
                gibibytes <= 4 -> 4
                else -> 8
            }
        }

        private val CHROME_VERSION_REGEX = Regex("Chrome/(\\d+)", RegexOption.IGNORE_CASE)
    }
}
