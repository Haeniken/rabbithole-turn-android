/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.subscription

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

class SubscriptionUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = try {
        if (Application.getSubscriptionManager().updateAll()) Result.success() else Result.retry()
    } catch (e: Throwable) {
        Log.w(TAG, "Unable to update subscriptions", e)
        Result.retry()
    }

    companion object {
        private const val TAG = "RabbitHole/SubscriptionWorker"
        private const val PERIODIC_WORK = "subscriptions-periodic"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(12, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build(),
            )
        }
    }
}
