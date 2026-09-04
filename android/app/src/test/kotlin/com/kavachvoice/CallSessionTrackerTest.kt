package com.kavachvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit Tests for CallSessionTracker:
 * - Call session isolation & monotonic session IDs
 * - Invalidation of stale VoiceID responses
 * - Multi-window temporal synthetic confirmation
 * - Safe reset upon call termination or genuine voice detection
 */
class CallSessionTrackerTest {

    private lateinit var tracker: CallSessionTracker

    @Before
    fun setUp() {
        tracker = CallSessionTracker()
    }

    @Test
    fun testNewCallGetsNewSessionId_AndResetsState() {
        tracker.startSession(101L)
        assertEquals(101L, tracker.activeSessionId)
        assertTrue(tracker.isCallActive)
        assertFalse(tracker.isSyntheticConfirmed)
        assertEquals(0, tracker.candidateSyntheticCount)

        // Simulate second call with new monotonic session ID
        tracker.startSession(102L)
        assertEquals(102L, tracker.activeSessionId)
        assertTrue(tracker.isCallActive)
        assertFalse(tracker.isSyntheticConfirmed)
    }

    @Test
    fun testCallTerminationResetsSyntheticState() {
        tracker.startSession(100L)

        // Window 1: synthetic
        val res1 = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.90f, sessionId = 100L)
        tracker.processVoiceIdResult(res1)
        assertEquals(1, tracker.candidateSyntheticCount)

        // Window 2: synthetic -> confirmed
        val res2 = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.92f, sessionId = 100L)
        tracker.processVoiceIdResult(res2)
        assertTrue("Synthetic should be confirmed after 2 windows", tracker.isSyntheticConfirmed)

        // Call ends
        tracker.endSession(100L)
        assertFalse(tracker.isCallActive)
        assertEquals(0L, tracker.activeSessionId)
        assertFalse("Termination must immediately reset synthetic confirmation", tracker.isSyntheticConfirmed)
        assertEquals(0, tracker.candidateSyntheticCount)
    }

    @Test
    fun testOldSessionVoiceIdResultIsIgnored() {
        tracker.startSession(100L)
        tracker.endSession(100L)

        // New call started
        tracker.startSession(101L)

        // Delayed result from old session 100L arrives
        val delayedOldResult = VoiceIdResult(
            isSuccess = true,
            verdict = "SYNTHETIC",
            confidence = 0.99f,
            sessionId = 100L
        )
        val accepted = tracker.processVoiceIdResult(delayedOldResult)

        assertFalse("Stale result from previous session must be rejected", accepted)
        assertFalse("Stale result must not confirm synthetic in new session", tracker.isSyntheticConfirmed)
        assertEquals(0, tracker.candidateSyntheticCount)
    }

    @Test
    fun testInactiveCallDiscardsVoiceIdResult() {
        // No active call
        val result = VoiceIdResult(
            isSuccess = true,
            verdict = "SYNTHETIC",
            confidence = 0.95f,
            sessionId = 100L
        )
        val accepted = tracker.processVoiceIdResult(result)

        assertFalse("VoiceID result when call is inactive must be rejected", accepted)
        assertFalse(tracker.isSyntheticConfirmed)
    }

    @Test
    fun testTemporalConfirmation_SingleSyntheticDoesNotConfirm() {
        tracker.startSession(200L)

        // Window 1: candidate synthetic (normal confidence < 0.95)
        val res1 = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.88f, sessionId = 200L)
        val accepted = tracker.processVoiceIdResult(res1)

        assertTrue(accepted)
        assertEquals(1, tracker.candidateSyntheticCount)
        assertFalse("Single synthetic window must NOT confirm RED prematurely", tracker.isSyntheticConfirmed)
    }

    @Test
    fun testTemporalConfirmation_TwoConsecutiveSyntheticWindowsConfirm() {
        tracker.startSession(200L)

        val res1 = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.88f, sessionId = 200L)
        tracker.processVoiceIdResult(res1)
        assertFalse(tracker.isSyntheticConfirmed)

        // Window 2: second synthetic
        val res2 = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.89f, sessionId = 200L)
        tracker.processVoiceIdResult(res2)

        assertTrue("Two consecutive synthetic windows must confirm synthetic voice", tracker.isSyntheticConfirmed)
        assertEquals(0.89f, tracker.confirmedConfidence)
    }

    @Test
    fun testGenuineWindowBreaksStaleSyntheticState() {
        tracker.startSession(200L)

        // Window 1: candidate synthetic
        val res1 = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.88f, sessionId = 200L)
        tracker.processVoiceIdResult(res1)
        assertEquals(1, tracker.candidateSyntheticCount)

        // Window 2: genuine voice detected
        val res2 = VoiceIdResult(isSuccess = true, verdict = "GENUINE", confidence = 0.10f, sessionId = 200L)
        tracker.processVoiceIdResult(res2)

        assertEquals("Genuine window must reset candidate counter", 0, tracker.candidateSyntheticCount)
        assertFalse(tracker.isSyntheticConfirmed)
    }

    @Test
    fun testUncertainWindowFailsSafeWithoutConfirming() {
        tracker.startSession(200L)

        // Window 1: candidate synthetic
        val res1 = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.88f, sessionId = 200L)
        tracker.processVoiceIdResult(res1)
        assertEquals(1, tracker.candidateSyntheticCount)

        // Window 2: silence / low energy -> UNCERTAIN
        val res2 = VoiceIdResult(isSuccess = true, verdict = "UNCERTAIN", confidence = 0.50f, sessionId = 200L)
        tracker.processVoiceIdResult(res2)

        assertFalse("UNCERTAIN must never confirm synthetic", tracker.isSyntheticConfirmed)
        assertEquals(0, tracker.candidateSyntheticCount)
    }

    @Test
    fun testManualDeveloperTestAsset_DirectConfirmation() {
        // Manual test assets in developer mode carry sessionId == 0L
        val devTestResult = VoiceIdResult(
            isSuccess = true,
            verdict = "SYNTHETIC",
            confidence = 0.99f,
            sessionId = 0L
        )
        val accepted = tracker.processVoiceIdResult(devTestResult)

        assertTrue(accepted)
        assertTrue("Manual reference test asset confirms synthetic", tracker.isSyntheticConfirmed)
        assertEquals(0.99f, tracker.confirmedConfidence)
    }

    // =========================================================================
    // PHASE 8 REGRESSION TESTS
    // =========================================================================

    @Test
    fun testRegression1_DeveloperSyntheticAsset_ActiveCall_PhonePe_ReturnsRed() {
        tracker.startSession(1001L)
        val devResult = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.8872f, sessionId = 0L)
        val accepted = tracker.processVoiceIdResult(devResult)
        assertTrue(accepted)
        assertTrue(tracker.isSyntheticConfirmed)

        val riskEngine = CallGuardRiskEngine()
        val state = CallGuardState(
            callActive = tracker.isCallActive,
            upiForeground = true,
            currentPackage = "com.phonepe.app",
            voiceCloneDetected = tracker.isSyntheticConfirmed,
            voiceCloneConfidence = tracker.confirmedConfidence
        )
        val verdict = riskEngine.evaluate(state)
        assertEquals(CallGuardRiskLevel.RED, verdict.level)
        assertTrue(verdict.isAlertActive)
    }

    @Test
    fun testRegression2_OneLiveSyntheticWindow_ReturnsNotRed() {
        tracker.startSession(1002L)
        val win1 = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.88f, sessionId = 1002L)
        val accepted = tracker.processVoiceIdResult(win1)
        assertTrue(accepted)
        assertEquals(1, tracker.candidateSyntheticCount)
        assertFalse(tracker.isSyntheticConfirmed)

        val riskEngine = CallGuardRiskEngine()
        val state = CallGuardState(
            callActive = tracker.isCallActive,
            upiForeground = true,
            currentPackage = "com.phonepe.app",
            voiceCloneDetected = tracker.isSyntheticConfirmed,
            voiceCloneConfidence = tracker.confirmedConfidence
        )
        val verdict = riskEngine.evaluate(state)
        assertEquals(CallGuardRiskLevel.ORANGE, verdict.level)
    }

    @Test
    fun testRegression3_TwoConsecutiveLiveSyntheticWindows_ReturnsRed() {
        tracker.startSession(1003L)
        val win1 = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.88f, sessionId = 1003L)
        tracker.processVoiceIdResult(win1)
        val win2 = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.91f, sessionId = 1003L)
        tracker.processVoiceIdResult(win2)
        assertTrue(tracker.isSyntheticConfirmed)

        val riskEngine = CallGuardRiskEngine()
        val state = CallGuardState(
            callActive = tracker.isCallActive,
            upiForeground = true,
            currentPackage = "com.phonepe.app",
            voiceCloneDetected = tracker.isSyntheticConfirmed,
            voiceCloneConfidence = tracker.confirmedConfidence
        )
        val verdict = riskEngine.evaluate(state)
        assertEquals(CallGuardRiskLevel.RED, verdict.level)
        assertTrue(verdict.isAlertActive)
    }

    @Test
    fun testRegression4_GenuineVoice_ReturnsOrange() {
        tracker.startSession(1004L)
        val win1 = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.88f, sessionId = 1004L)
        tracker.processVoiceIdResult(win1)
        // Genuine breaks candidate
        val genuine = VoiceIdResult(isSuccess = true, verdict = "GENUINE", confidence = 0.05f, sessionId = 1004L)
        tracker.processVoiceIdResult(genuine)
        assertFalse(tracker.isSyntheticConfirmed)
        assertEquals(0, tracker.candidateSyntheticCount)

        val riskEngine = CallGuardRiskEngine()
        val state = CallGuardState(
            callActive = tracker.isCallActive,
            upiForeground = true,
            currentPackage = "com.phonepe.app",
            voiceCloneDetected = tracker.isSyntheticConfirmed,
            voiceCloneConfidence = tracker.confirmedConfidence
        )
        val verdict = riskEngine.evaluate(state)
        assertEquals(CallGuardRiskLevel.ORANGE, verdict.level)
    }

    @Test
    fun testRegression5_UncertainVoice_ReturnsOrange() {
        tracker.startSession(1005L)
        val uncertain = VoiceIdResult(isSuccess = true, verdict = "UNCERTAIN", confidence = 0.50f, sessionId = 1005L)
        tracker.processVoiceIdResult(uncertain)
        assertFalse(tracker.isSyntheticConfirmed)

        val riskEngine = CallGuardRiskEngine()
        val state = CallGuardState(
            callActive = tracker.isCallActive,
            upiForeground = true,
            currentPackage = "com.phonepe.app",
            voiceCloneDetected = tracker.isSyntheticConfirmed,
            voiceCloneConfidence = tracker.confirmedConfidence
        )
        val verdict = riskEngine.evaluate(state)
        assertEquals(CallGuardRiskLevel.ORANGE, verdict.level)
    }

    @Test
    fun testRegression6_SyntheticVoice_NoPhonePe_ReturnsGreen() {
        tracker.startSession(1006L)
        val devResult = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.99f, sessionId = 0L)
        tracker.processVoiceIdResult(devResult)
        assertTrue(tracker.isSyntheticConfirmed)

        val riskEngine = CallGuardRiskEngine()
        val state = CallGuardState(
            callActive = tracker.isCallActive,
            upiForeground = false,
            currentPackage = null,
            voiceCloneDetected = tracker.isSyntheticConfirmed,
            voiceCloneConfidence = tracker.confirmedConfidence
        )
        val verdict = riskEngine.evaluate(state)
        assertEquals(CallGuardRiskLevel.GREEN, verdict.level)
        assertFalse(verdict.isAlertActive)
    }

    @Test
    fun testRegression7_StaleSyntheticResultFromPreviousSession_Ignored() {
        tracker.startSession(1007L)
        tracker.endSession(1007L)
        tracker.startSession(1008L)

        val staleResult = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.99f, sessionId = 1007L)
        val accepted = tracker.processVoiceIdResult(staleResult)
        assertFalse(accepted)
        assertFalse(tracker.isSyntheticConfirmed)
        assertEquals(0, tracker.candidateSyntheticCount)
    }

    @Test
    fun testRegression8_CallEndsAfterSyntheticDetection_RedClearedImmediately() {
        tracker.startSession(1009L)
        val devResult = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.99f, sessionId = 0L)
        tracker.processVoiceIdResult(devResult)
        assertTrue(tracker.isSyntheticConfirmed)

        // Call ends
        tracker.endSession(1009L)
        assertFalse(tracker.isCallActive)
        assertFalse(tracker.isSyntheticConfirmed)

        val riskEngine = CallGuardRiskEngine()
        val state = CallGuardState(
            callActive = tracker.isCallActive,
            upiForeground = true,
            currentPackage = "com.phonepe.app",
            voiceCloneDetected = tracker.isSyntheticConfirmed,
            voiceCloneConfidence = tracker.confirmedConfidence
        )
        val verdict = riskEngine.evaluate(state)
        assertEquals(CallGuardRiskLevel.GREEN, verdict.level)
        assertFalse(verdict.isAlertActive)
    }
}
