/*
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wireguard.android.R

/** Posts a heads-up entry that can reopen a pending manual CAPTCHA request. */
internal object CaptchaNotification {
    private const val TAG = "WireGuard/CaptchaNotification"
    private const val CHANNEL_ID = "captcha_required"
    private const val NOTIFICATION_ID = 0xCA71

    fun show(context: Context, requestId: String, redirectUri: String) {
        createChannel(context)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Notification permission is unavailable; relying on direct CAPTCHA activity launch")
            return
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            requestId.hashCode(),
            CaptchaActivity.createIntent(context, requestId, redirectUri),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setColor(ContextCompat.getColor(context, R.color.rabbit_accent))
            .setContentTitle(context.getString(R.string.captcha_notification_title))
            .setContentText(context.getString(R.string.captcha_notification_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.captcha_notification_text)))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setOngoing(true)
            .setTimeoutAfter(CaptchaCoordinator.CAPTCHA_TIMEOUT_MILLIS)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to post CAPTCHA notification", e)
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.captcha_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.captcha_notification_channel_description)
            enableVibration(true)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
