package com.kavachvoice

/**
 * CallSessionTracker enforces:
 * 1. Call Session Isolation (monotonic sessionId validation, dropping stale or delayed results).
 * 2. Multi-window Temporal Confirmation (requires 2 consecutive synthetic windows to confirm RED,
 *    unless a high-confidence reference test asset is evaluated).
 * 3. Immediate state cleanup on call termination.
 * 4. Safe recovery on genuine or uncertain speech windows.
 *
 * Pure Kotlin — fully testable in JVM unit tests without Android OS mocking.
 */
class CallSessionTracker {

    var activeSessionId: Long = 0L
        private set

    var isCallActive: Boolean = false
        private set

    var candidateSyntheticCount: Int = 0
        private set

    var isSyntheticConfirmed: Boolean = false
        private set

    var confirmedConfidence: Float = 0.0f
        private set

    /**
     * Called when a call session starts.
     */
    fun startSession(sessionId: Long) {
        synchronized(this) {
            activeSessionId = sessionId
            isCallActive = true
            resetSyntheticState()
        }
    }

    /**
     * Called when a call session ends. Immediately invalidates session and resets state.
     */
    fun endSession(sessionId: Long) {
        synchronized(this) {
            isCallActive = false
            activeSessionId = 0L
            resetSyntheticState()
        }
    }

    /**
     * Resets temporal synthetic accumulation state.
     */
    fun resetSyntheticState() {
        synchronized(this) {
            candidateSyntheticCount = 0
            isSyntheticConfirmed = false
            confirmedConfidence = 0.0f
        }
    }

    /**
     * Processes incoming VoiceIdResult.
     * @return true if accepted and applied; false if discarded due to stale session or inactive call.
     */
    fun processVoiceIdResult(result: VoiceIdResult): Boolean {
        synchronized(this) {
            // Discard stale result if sessionId does not match active call session
            if (activeSessionId != 0L && result.sessionId != 0L && result.sessionId != activeSessionId) {
                return false
            }

            // Discard result if call is no longer active (unless manual dev test with sessionId == 0L)
            if (!isCallActive && result.sessionId != 0L) {
                return false
            }

            if (result.isSuccess && result.verdict == "SYNTHETIC") {
                if (result.sessionId == 0L) {
                    // Explicit developer/reference test asset path: immediate confirmation
                    candidateSyntheticCount = 2
                    isSyntheticConfirmed = true
                    confirmedConfidence = result.confidence
                } else {
                    // Live production audio: strictly require 2 consecutive synthetic windows
                    candidateSyntheticCount++
                    if (candidateSyntheticCount >= 2) {
                        isSyntheticConfirmed = true
                        confirmedConfidence = result.confidence
                    } else {
                        isSyntheticConfirmed = false
                    }
                }
            } else if (result.verdict == "GENUINE") {
                // Genuine speech immediately breaks and clears candidate synthetic state
                candidateSyntheticCount = 0
                isSyntheticConfirmed = false
                confirmedConfidence = 0.0f
            } else {
                // UNCERTAIN or UNAVAILABLE fails safe: decrement candidate counter and clear confirmation
                if (candidateSyntheticCount > 0) candidateSyntheticCount--
                if (candidateSyntheticCount < 2) {
                    isSyntheticConfirmed = false
                    confirmedConfidence = 0.0f
                }
            }
            return true
        }
    }
}
