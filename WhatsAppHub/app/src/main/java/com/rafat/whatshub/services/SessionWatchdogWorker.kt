package com.rafat.whatshub.services

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rafat.whatshub.WhatsAppHubApp
import com.rafat.whatshub.database.AccountStatus
import java.util.concurrent.TimeUnit

class SessionWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? WhatsAppHubApp ?: return Result.success()
        val repo = app.repository
        repo.initializeEngineAndRestoreSessions()
        for (acct in repo.database.getAccounts()) {
            if (acct.hasSavedSession &&
                (acct.status == AccountStatus.RECONNECTING || acct.status == AccountStatus.CONNECTION_ERROR)
            ) {
                repo.connectAccount(acct.accountId)
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "whatshub_session_watchdog"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<SessionWatchdogWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }
}
