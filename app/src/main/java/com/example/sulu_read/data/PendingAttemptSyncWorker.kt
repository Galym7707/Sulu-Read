package com.example.sulu_read.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class PendingAttemptSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val store = PendingAttemptStore(applicationContext)
        val attempts = store.all()
        if (attempts.isEmpty()) return Result.success()

        val syncedIds = mutableSetOf<String>()
        attempts.forEach { attempt ->
            runCatching {
                ApiClient.submitExerciseAttempt(
                    userId = attempt.userId,
                    exerciseType = attempt.exerciseType,
                    subExercise = attempt.subExercise,
                    targetWord = attempt.targetWord,
                    correctAnswer = attempt.correctAnswer,
                    userAnswer = attempt.userAnswer,
                    responseTimeMs = attempt.responseTimeMs,
                    difficultyLevel = attempt.difficultyLevel,
                    languageHint = attempt.languageHint
                )
            }.onSuccess {
                syncedIds.add(attempt.id)
            }
        }

        store.removeSynced(syncedIds)
        return if (syncedIds.size == attempts.size) Result.success() else Result.retry()
    }
}

object PendingAttemptSyncScheduler {
    private const val UNIQUE_PERIODIC_WORK = "sulu_read_pending_attempt_periodic_sync"
    private const val UNIQUE_ONE_TIME_WORK = "sulu_read_pending_attempt_immediate_sync"

    fun schedulePeriodic(context: Context) {
        val constraints = syncConstraints()
        val request = PeriodicWorkRequestBuilder<PendingAttemptSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun enqueueImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<PendingAttemptSyncWorker>()
            .setConstraints(syncConstraints())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_ONE_TIME_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun syncConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
