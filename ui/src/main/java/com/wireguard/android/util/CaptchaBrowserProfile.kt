/*
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/** Browser identity shared by native VK authorization and the CAPTCHA WebView. */
class CaptchaBrowserProfile private constructor(
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
        private const val CHROME_MAJOR = 146

        @Volatile
        private var cached: CaptchaBrowserProfile? = null

        fun get(context: Context): CaptchaBrowserProfile {
            cached?.let { return it }
            return synchronized(this) {
                cached ?: create(context.applicationContext).also { cached = it }
            }
        }

        private fun create(context: Context): CaptchaBrowserProfile {
            val metrics = context.resources.displayMetrics
            val density = metrics.density.takeIf { it > 0f } ?: 1f
            val width = (metrics.widthPixels / density).roundToInt().coerceAtLeast(1)
            val height = (metrics.heightPixels / density).roundToInt().coerceAtLeast(1)
            val locale = Locale.getDefault()
            val language = locale.toLanguageTag().ifBlank { "en-US" }
            val languages = listOf(language, locale.language, "en").filter { it.isNotBlank() }.distinct()
            val model = Build.MODEL.replace(Regex("[();]"), " ").trim().ifBlank { "Android" }
            val androidVersion = Build.VERSION.RELEASE.ifBlank { "14" }
            val userAgent = "Mozilla/5.0 (Linux; Android $androidVersion; $model) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROME_MAJOR.0.0.0 Mobile Safari/537.36"
            val navigatorPlatform = if (Build.SUPPORTED_ABIS.firstOrNull()?.contains("x86") == true) {
                "Linux x86_64"
            } else {
                "Linux armv81"
            }

            return CaptchaBrowserProfile(
                userAgent = userAgent,
                secChUa = "\"Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"$CHROME_MAJOR\", \"Chromium\";v=\"$CHROME_MAJOR\"",
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
            )
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
    }
}
