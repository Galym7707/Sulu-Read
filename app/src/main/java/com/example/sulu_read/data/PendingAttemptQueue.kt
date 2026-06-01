package com.example.sulu_read.data

import kotlinx.coroutines.flow.Flow

interface PendingAttemptQueue {
    val pendingCount: Flow<Int>
    suspend fun enqueue(attempt: PendingAttempt)
}
