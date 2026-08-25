/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.routing

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.BackoffPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wireguard.android.Application
import com.wireguard.android.util.PowerPolicySettings
import java.util.concurrent.TimeUnit

class RoutingListUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = if (!Application.getRoutingListManager().hasData()) {
        // The first download belongs to the first ru-direct connection (or an explicit manual request).
        Result.success()
    } else try {
        Application.getRoutingListManager().update()
        Result.success()
    } catch (e: Throwable) {
        Log.w(TAG, "Unable to update routing lists", e)
        Result.retry()
    }

    companion object {
        private const val TAG = "RabbitHole/RoutingWorker"
        private const val PERIODIC_WORK = "routing-lists-periodic"
        private const val STARTUP_WORK = "routing-lists-startup"

        fun configureAtStartup(
            context: Context,
            hasData: Boolean,
            powerPolicy: PowerPolicySettings.Snapshot = PowerPolicySettings.current(),
        ) {
            if (!powerPolicy.backgroundUpdatesEnabled) {
                cancelAll(context)
                return
            }
            scheduleStartup(context, powerPolicy)
            if (hasData) schedulePeriodic(context, powerPolicy)
            else WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        }

        fun scheduleStartup(
            context: Context,
            powerPolicy: PowerPolicySettings.Snapshot = PowerPolicySettings.current(),
        ) {
            if (!powerPolicy.backgroundUpdatesEnabled) {
                WorkManager.getInstance(context).cancelUniqueWork(STARTUP_WORK)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(powerPolicy.powerSavingEnabled)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                STARTUP_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RoutingListUpdateWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                    .build(),
            )
        }

        fun schedulePeriodic(
            context: Context,
            powerPolicy: PowerPolicySettings.Snapshot = PowerPolicySettings.current(),
        ) {
            if (!powerPolicy.backgroundUpdatesEnabled) {
                WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(powerPolicy.powerSavingEnabled)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<RoutingListUpdateWorker>(24, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                    .build(),
            )
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(STARTUP_WORK)
                cancelUniqueWork(PERIODIC_WORK)
            }
        }
    }
}
