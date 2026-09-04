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
        assertEquals("UNCERTAIN does not increase candidate count (stays 1)", 1, tracker.candidateSyntheticCount)
    }

    @Test
    fun testUnavailableResultFailsSafeWithoutConfirming() {
        tracker.startSession(200L)

        // Window 1: candidate synthetic
        val res1 = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.88f, sessionId = 200L)
        tracker.processVoiceIdResult(res1)
        assertEquals(1, tracker.candidateSyntheticCount)

        // Window 2: network error or backend failure -> UNAVAILABLE
        val res2 = VoiceIdResult(isSuccess = false, verdict = "UNAVAILABLE", confidence = 0.0f, sessionId = 200L)
        val accepted = tracker.processVoiceIdResult(res2)

        assertTrue(accepted)
        assertFalse("UNAVAILABLE must never confirm synthetic", tracker.isSyntheticConfirmed)
        assertEquals("UNAVAILABLE does not increase candidate count (stays 1)", 1, tracker.candidateSyntheticCount)
    }

    @Test
    fun testSessionTransitionInvalidatesOldResults() {
        // Session 301 starts
        tracker.startSession(301L)
        assertEquals(301L, tracker.activeSessionId)

        // Session 301 ends
        tracker.endSession(301L)
        assertEquals(0L, tracker.activeSessionId)
        assertFalse(tracker.isCallActive)

        // Session 302 starts
        tracker.startSession(302L)
        assertEquals(302L, tracker.activeSessionId)

        // Result from session 301 arrives during session 302
        val oldRes = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.95f, sessionId = 301L)
        val accepted = tracker.processVoiceIdResult(oldRes)

        assertFalse("Old session result must be rejected during session transition", accepted)
        assertFalse("Old session result must not confirm synthetic in new session", tracker.isSyntheticConfirmed)
        assertEquals(0, tracker.candidateSyntheticCount)
    }

    // =========================================================================
    // SECTION 5 ADVERSARIAL SESSION RACE CONDITION TESTS
    // =========================================================================

    @Test
    fun testAdversarialCase1_CallA_SyntheticWin1_CallEnds_OldResultArrives() {
        tracker.startSession(601L)
        val win1 = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.88f, sessionId = 601L)
        tracker.processVoiceIdResult(win1)
        assertEquals(1, tracker.candidateSyntheticCount)

        // Call A ends
        tracker.endSession(601L)
        assertFalse(tracker.isCallActive)

        // Old synthetic result from Call A arrives after termination
        val delayedOldResult = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.92f, sessionId = 601L)
        val accepted = tracker.processVoiceIdResult(delayedOldResult)

        assertFalse("Result after call end must be discarded", accepted)
        assertFalse("Cannot confirm synthetic after call termination", tracker.isSyntheticConfirmed)
        assertEquals(0, tracker.candidateSyntheticCount)
    }

    @Test
    fun testAdversarialCase2_CallA_InferenceRunning_CallAEnds_CallBBegins_CallAResultArrives() {
        tracker.startSession(701L)
        // Call A ends before inference finishes
        tracker.endSession(701L)

        // Call B begins
        tracker.startSession(702L)

        // Call A delayed result arrives
        val callAResult = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.99f, sessionId = 701L)
        val accepted = tracker.processVoiceIdResult(callAResult)

        assertFalse("Mismatched session result from previous call must be discarded", accepted)
        assertFalse("Call A result must not confirm Call B", tracker.isSyntheticConfirmed)
        assertEquals(0, tracker.candidateSyntheticCount)
    }

    @Test
    fun testAdversarialCase3_CallA_Candidate1_CallEnds_CallBBegins_CandidateIsZero() {
        tracker.startSession(801L)
        val win1 = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.88f, sessionId = 801L)
        tracker.processVoiceIdResult(win1)
        assertEquals(1, tracker.candidateSyntheticCount)

        // Call A ends
        tracker.endSession(801L)
        assertEquals(0, tracker.candidateSyntheticCount)

        // Call B begins
        tracker.startSession(802L)
        assertEquals("New call session must start with candidate count 0", 0, tracker.candidateSyntheticCount)
        assertFalse(tracker.isSyntheticConfirmed)
    }

    @Test
    fun testAdversarialCase4_CallA_RedConfirmed_CallEnds_RedClearedImmediately() {
        tracker.startSession(901L)
        val win1 = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.88f, sessionId = 901L)
        tracker.processVoiceIdResult(win1)
        val win2 = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.91f, sessionId = 901L)
        tracker.processVoiceIdResult(win2)
        assertTrue("Synthetic must be confirmed", tracker.isSyntheticConfirmed)

        // Call ends
        tracker.endSession(901L)
        assertFalse("RED state must be cleared immediately upon call end", tracker.isSyntheticConfirmed)
        assertEquals(0, tracker.candidateSyntheticCount)
        assertEquals(0.0f, tracker.confirmedConfidence, 0.0001f)
    }

    @Test
    fun testAdversarialCase5_CallA_Candidate1_GenuineSpeech_Synthetic_FirstReset() {
        tracker.startSession(1001L)
        // Synthetic 1
        val syn1 = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.88f, sessionId = 1001L)
        tracker.processVoiceIdResult(syn1)
        assertEquals(1, tracker.candidateSyntheticCount)

        // Genuine speech arrives
        val genuine = VoiceIdResult(isSuccess = true, verdict = "GENUINE", confidence = 0.05f, sessionId = 1001L)
        tracker.processVoiceIdResult(genuine)
        assertEquals("Genuine speech must reset candidate count to 0", 0, tracker.candidateSyntheticCount)
        assertFalse(tracker.isSyntheticConfirmed)

        // Subsequent synthetic arrives -> starts fresh sequence from 1
        val syn2 = VoiceIdResult(isSuccess = true, verdict = "SYNTHETIC", confidence = 0.89f, sessionId = 1001L)
        tracker.processVoiceIdResult(syn2)
        assertEquals("New synthetic sequence starts from 1, NOT 2", 1, tracker.candidateSyntheticCount)
        assertFalse("Single synthetic after genuine reset must NOT confirm RED", tracker.isSyntheticConfirmed)
    }

    @Test
    fun testAdversarialCase6_BackendUnavailable_BackendReturns_GenuineSpeech_NoAccidentalRed() {
        tracker.startSession(1101L)

        // Backend unavailable
        val unavail = VoiceIdResult(isSuccess = false, verdict = "UNAVAILABLE", confidence = 0.0f, sessionId = 1101L)
        tracker.processVoiceIdResult(unavail)
        assertFalse(tracker.isSyntheticConfirmed)
        assertEquals(0, tracker.candidateSyntheticCount)

        // Backend returns and reports genuine human voice
        val genuine = VoiceIdResult(isSuccess = true, verdict = "GENUINE", confidence = 0.08f, sessionId = 1101L)
        tracker.processVoiceIdResult(genuine)
        assertFalse("Backend recovery with genuine voice must remain safe", tracker.isSyntheticConfirmed)
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

    @Test
    fun testLiveSessionCannotUseReferenceBypass_EvenWithHighConfidence() {
        tracker.startSession(5001L)

        // Window 1: extremely high confidence live result (0.999f)
        val highConfLiveResult = VoiceIdResult(
            isSuccess = true,
            verdict = "SYNTHETIC",
            confidence = 0.999f,
            rawnet2Score = 0.999f,
            sessionId = 5001L
        )
        val accepted = tracker.processVoiceIdResult(highConfLiveResult)

        assertTrue(accepted)
        assertEquals("Live audio must increment candidate to 1", 1, tracker.candidateSyntheticCount)
        assertFalse("Live audio must NEVER bypass temporal confirmation on window 1, even with 0.999 confidence", tracker.isSyntheticConfirmed)

        // Window 2 confirms
        val win2 = VoiceIdResult(
            isSuccess = true,
            verdict = "SYNTHETIC",
            confidence = 0.985f,
            rawnet2Score = 0.985f,
            sessionId = 5001L
        )
        tracker.processVoiceIdResult(win2)
        assertEquals(2, tracker.candidateSyntheticCount)
        assertTrue("Two consecutive windows confirm synthetic", tracker.isSyntheticConfirmed)
    }
}
