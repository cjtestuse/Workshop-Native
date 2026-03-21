package com.slay.workshopnative.core.logging

object MainActivityRuntimeTracker {
    private val lock = Any()
    private val activeInstanceIds = linkedSetOf<String>()
    private var createCount = 0
    private var newIntentCount = 0
    private var destroyCount = 0
    private var maxActiveCount = 0
    private var duplicateDetected = false
    private var lastDuplicateDetectedAtMs: Long? = null
    private var lastCreatedAtMs: Long? = null
    private var lastNewIntentAtMs: Long? = null
    private var lastDestroyedAtMs: Long? = null
    private var lastResumedAtMs: Long? = null
    private var lastStoppedAtMs: Long? = null
    private var lastTaskId: Int? = null
    private var lastIntentFlags: Int? = null
    private var lastLaunchHadSavedState: Boolean? = null
    private var lastDestroyedChangingConfigurations: Boolean? = null
    private var lastCreatedInstanceId: String? = null
    private var lastResumedInstanceId: String? = null

    fun onCreated(
        instanceId: String,
        taskId: Int,
        intentFlags: Int?,
        hadSavedState: Boolean,
    ) {
        synchronized(lock) {
            createCount += 1
            lastCreatedAtMs = System.currentTimeMillis()
            lastTaskId = taskId
            lastIntentFlags = intentFlags
            lastLaunchHadSavedState = hadSavedState
            lastCreatedInstanceId = instanceId
            activeInstanceIds += instanceId
            if (activeInstanceIds.size > maxActiveCount) {
                maxActiveCount = activeInstanceIds.size
            }
            if (activeInstanceIds.size > 1) {
                duplicateDetected = true
                lastDuplicateDetectedAtMs = lastCreatedAtMs
            }
        }
    }

    fun onNewIntent(
        instanceId: String,
        taskId: Int,
        intentFlags: Int?,
    ) {
        synchronized(lock) {
            newIntentCount += 1
            lastNewIntentAtMs = System.currentTimeMillis()
            lastTaskId = taskId
            lastIntentFlags = intentFlags
            lastResumedInstanceId = instanceId
        }
    }

    fun onResumed(instanceId: String, taskId: Int) {
        synchronized(lock) {
            lastResumedAtMs = System.currentTimeMillis()
            lastTaskId = taskId
            lastResumedInstanceId = instanceId
            activeInstanceIds += instanceId
            if (activeInstanceIds.size > maxActiveCount) {
                maxActiveCount = activeInstanceIds.size
            }
        }
    }

    fun onStopped(instanceId: String, taskId: Int) {
        synchronized(lock) {
            lastStoppedAtMs = System.currentTimeMillis()
            lastTaskId = taskId
            lastResumedInstanceId = instanceId
        }
    }

    fun onDestroyed(
        instanceId: String,
        taskId: Int,
        changingConfigurations: Boolean,
    ) {
        synchronized(lock) {
            destroyCount += 1
            lastDestroyedAtMs = System.currentTimeMillis()
            lastTaskId = taskId
            lastDestroyedChangingConfigurations = changingConfigurations
            activeInstanceIds -= instanceId
        }
    }

    fun snapshot(): SupportActivityRuntimeSnapshot {
        synchronized(lock) {
            return SupportActivityRuntimeSnapshot(
                mainActivityCreateCount = createCount,
                mainActivityNewIntentCount = newIntentCount,
                mainActivityDestroyCount = destroyCount,
                activeMainActivityCount = activeInstanceIds.size,
                maxActiveMainActivityCount = maxActiveCount,
                duplicateMainActivityDetected = duplicateDetected,
                lastDuplicateDetectedAtMs = lastDuplicateDetectedAtMs,
                lastCreatedAtMs = lastCreatedAtMs,
                lastNewIntentAtMs = lastNewIntentAtMs,
                lastDestroyedAtMs = lastDestroyedAtMs,
                lastResumedAtMs = lastResumedAtMs,
                lastStoppedAtMs = lastStoppedAtMs,
                lastTaskId = lastTaskId,
                lastIntentFlags = lastIntentFlags,
                lastLaunchHadSavedState = lastLaunchHadSavedState,
                lastDestroyedChangingConfigurations = lastDestroyedChangingConfigurations,
                lastCreatedInstanceId = lastCreatedInstanceId,
                lastResumedInstanceId = lastResumedInstanceId,
            )
        }
    }
}
