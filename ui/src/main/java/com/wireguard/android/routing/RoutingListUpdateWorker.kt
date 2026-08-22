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
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wireguard.android.Application
import java.util.concurrent.TimeUnit

class RoutingListUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = try {
        Application.getRoutingListManager().update()
        Result.success()
    } catch (e: Throwable) {
        Log.w(TAG, "Unable to update routing lists", e)
        Result.retry()
    }

    companion object {
        private const val TAG = "RabbitHole/RoutingWorker"
        private const val PERIODIC_WORK = "routing-lists-periodic"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<RoutingListUpdateWorker>(24, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build(),
            )
        }
    }
}
