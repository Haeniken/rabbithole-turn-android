/*
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Serializes CAPTCHA UI and keeps results bound to the request that created it. */
internal object CaptchaCoordinator {
    private const val TAG = "WireGuard/CaptchaCoordinator"
    private const val CAPTCHA_TIMEOUT_SECONDS = 120L
    internal const val CAPTCHA_TIMEOUT_MILLIS = CAPTCHA_TIMEOUT_SECONDS * 1_000L
    private const val ACTIVITY_CLOSE_TIMEOUT_SECONDS = 5L
    private val requestGate = Semaphore(1, true)
    private val activeRequests = ConcurrentHashMap<String, Request>()

    private class Request(val context: Context) {
        val result = CompletableFuture<String>()
        val activityClosed = CompletableFuture<Unit>()

        @Volatile
        var activity: WeakReference<CaptchaActivity>? = null
    }

    fun solve(context: Context, redirectUri: String): String {
        try {
            requestGate.acquire()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return ""
        }

        val requestId = UUID.randomUUID().toString()
        val request = Request(context.applicationContext)
        activeRequests[requestId] = request
        return try {
            CaptchaNotification.show(request.context, requestId, redirectUri)
            context.startActivity(CaptchaActivity.createIntent(context, requestId, redirectUri))
            try {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                    .appTasks
                    .firstOrNull()
                    ?.moveToFront()
            } catch (e: Throwable) {
                Log.w(TAG, "Unable to bring CAPTCHA task to foreground", e)
            }
            request.result.get(CAPTCHA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            Log.e(TAG, "CAPTCHA request timed out: $requestId")
            ""
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ""
        } catch (e: Exception) {
            Log.e(TAG, "CAPTCHA request failed: $requestId", e)
            ""
        } finally {
            CaptchaNotification.cancel(request.context)
            val activity = request.activity?.get()
            if (activity != null) {
                activity.runOnUiThread { activity.finish() }
                try {
                    request.activityClosed.get(ACTIVITY_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    // The result is final; avoid blocking a later request indefinitely.
                }
            }
            activeRequests.remove(requestId, request)
            requestGate.release()
        }
    }

    fun attach(requestId: String, activity: CaptchaActivity): Boolean {
        val request = activeRequests[requestId] ?: return false
        request.activity = WeakReference(activity)
        return true
    }

    fun detach(requestId: String, activity: CaptchaActivity) {
        val request = activeRequests[requestId] ?: return
        if (request.activity?.get() === activity) {
            request.activity = null
            request.activityClosed.complete(Unit)
        }
    }

    fun complete(requestId: String, token: String): Boolean {
        val request = activeRequests[requestId] ?: return false
        val completed = request.result.complete(token)
        if (completed) CaptchaNotification.cancel(request.context)
        return completed
    }

    fun cancelActive() {
        activeRequests.forEach { (requestId, request) ->
            if (request.result.complete("")) {
                Log.d(TAG, "CAPTCHA request cancelled with tunnel startup: $requestId")
                CaptchaNotification.cancel(request.context)
            }
        }
    }
}
