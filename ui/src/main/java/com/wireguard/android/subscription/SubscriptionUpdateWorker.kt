/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.subscription

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wireguard.android.Application
import java.util.concurrent.TimeUnit

class SubscriptionUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = try {
        Log.i(TAG, "Automatic subscription check started")
        if (Application.getSubscriptionManager().updateAll()) {
            Log.i(TAG, "Automatic subscription check finished")
            Result.success()
        } else {
            Log.w(TAG, "Automatic subscription check completed with errors")
            Result.retry()
        }
    } catch (e: Throwable) {
        Log.w(TAG, "Unable to update subscriptions", e)
        Result.retry()
    }

    companion object {
        private const val TAG = "RabbitHole/SubscriptionWorker"
        private const val PERIODIC_WORK = "subscriptions-periodic"

        suspend fun configureAtStartup(context: Context) {
            val settings = SubscriptionSettings.load(Application.getPreferencesDataStore())
            schedulePeriodic(context, settings.automaticUpdates, settings.intervalHours)
            if (settings.updateOnOpen) {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    STARTUP_WORK,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<SubscriptionUpdateWorker>()
                        .setConstraints(networkConstraints())
                        .build(),
                )
            } else {
                WorkManager.getInstance(context).cancelUniqueWork(STARTUP_WORK)
            }
        }

        fun schedulePeriodic(context: Context, enabled: Boolean, intervalHours: Int) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelUniqueWork(PERIODIC_WORK)
                return
            }
            val normalizedInterval = intervalHours.coerceIn(
                SubscriptionSettings.MIN_UPDATE_INTERVAL_HOURS,
                SubscriptionSettings.MAX_UPDATE_INTERVAL_HOURS,
            )
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(normalizedInterval.toLong(), TimeUnit.HOURS)
                    .setConstraints(networkConstraints())
                    .build(),
            )
        }

        private fun networkConstraints(): Constraints {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            return constraints
        }

        private const val STARTUP_WORK = "subscriptions-startup"
    }
}
