package com.kavachvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Focused Android Unit Tests for CallGuard Multi-Signal Risk Engine.
 *
 * Verifies the ratified truth table and regression cases:
 * 1. no call + no UPI = GREEN
 * 2. no call + UPI = GREEN
 * 3. call + no UPI = GREEN
 * 4. call + UPI = ORANGE
 * 5. call + UPI + fraud = RED
 * 6. call + UPI + urgency = ORANGE
 * 7. call + UPI + fraud + urgency = RED
 * 8. unrelated notification/window event = no alert
 * 9. demo trigger = deterministic fraud signal
 */
class CallGuardRiskEngineTest {

    private lateinit var riskEngine: CallGuardRiskEngine

    @Before
    fun setUp() {
        riskEngine = CallGuardRiskEngine()
    }

    @Test
    fun testCase1_NoCall_NoUpi_ReturnsGreen() {
        val state = CallGuardState(
            callActive = false,
            upiForeground = false
        )
        val verdict = riskEngine.evaluate(state)

        assertEquals(CallGuardRiskLevel.GREEN, verdict.level)
        assertFalse("Alert must be inactive when safe", verdict.isAlertActive)
        assertTrue(verdict.explanation.contains("No active call"))
    }

    @Test
    fun testCase2_NoCall_WithUpi_ReturnsGreen() {
        val state = CallGuardState(
            callActive = false,
            upiForeground = true,
            currentPackage = "com.phonepe.app"
        )
        val verdict = riskEngine.evaluate(state)

        assertEquals(CallGuardRiskLevel.GREEN, verdict.level)
        assertFalse("Benign UPI usage without call must not trigger alert", verdict.isAlertActive)
        assertTrue(verdict.explanation.contains("PhonePe"))
        assertTrue(verdict.explanation.contains("no active phone call"))
    }

    @Test
    fun testCase3_CallActive_NoUpi_ReturnsGreenMonitoring() {
        val state = CallGuardState(
            callActive = true,
            upiForeground = false
        )
        val verdict = riskEngine.evaluate(state)

        assertEquals(CallGuardRiskLevel.GREEN, verdict.level)
        assertFalse("Normal call without financial app must not trigger alert", verdict.isAlertActive)
        assertTrue(verdict.explanation.contains("Monitoring"))
    }

    @Test
    fun testCase4_CallActive_WithUpi_ReturnsOrange() {
        val state = CallGuardState(
            callActive = true,
            upiForeground = true,
            currentPackage = "com.phonepe.app"
        )
        val verdict = riskEngine.evaluate(state)

        assertEquals(CallGuardRiskLevel.ORANGE, verdict.level)
        assertTrue("Active call + UPI foreground must activate ORANGE warning", verdict.isAlertActive)
        assertTrue(verdict.explanation.contains("PhonePe"))
        assertTrue(verdict.explanation.contains("High-risk financial context"))
    }

    @Test
    fun testCase5_CallActive_WithUpi_WithFraudKeyword_ReturnsOrange() {
        val state = CallGuardState(
            callActive = true,
            upiForeground = true,
            currentPackage = "net.one97.paytm",
            fraudKeywordDetected = true,
            detectedFraudKeywords = listOf("otp", "बताइए")
        )
        val verdict = riskEngine.evaluate(state)

        assertEquals("Fraud keyword during Call+UPI remains ORANGE contextual warning (NOT cloned voice)", CallGuardRiskLevel.ORANGE, verdict.level)
        assertTrue("Alert must be active", verdict.isAlertActive)
        assertTrue(verdict.explanation.contains("Paytm"))
        assertTrue(verdict.explanation.contains("otp, बताइए"))
    }

    @Test
    fun testCase6_CallActive_WithUpi_WithUrgencyOnly_ReturnsOrange() {
        val state = CallGuardState(
            callActive = true,
            upiForeground = true,
            currentPackage = "com.google.android.apps.nbu.paisa.user",
            urgencyDetected = true,
            detectedUrgencyKeywords = listOf("jaldi", "तुरंत")
        )
        val verdict = riskEngine.evaluate(state)

        assertEquals(CallGuardRiskLevel.ORANGE, verdict.level)
        assertTrue("Urgency language alone during call+UPI remains ORANGE, not RED", verdict.isAlertActive)
        assertTrue(verdict.explanation.contains("Google Pay"))
        assertTrue(verdict.explanation.contains("jaldi, तुरंत"))
    }

    @Test
    fun testCase7_CallActive_WithUpi_WithFraudAndUrgency_ReturnsOrange() {
        val state = CallGuardState(
            callActive = true,
            upiForeground = true,
            currentPackage = "in.org.npci.upiapp",
            fraudKeywordDetected = true,
            urgencyDetected = true,
            detectedFraudKeywords = listOf("pin", "password"),
            detectedUrgencyKeywords = listOf("arrest", "police")
        )
        val verdict = riskEngine.evaluate(state)

        assertEquals("Call + UPI + Fraud + Urgency language remains elevated ORANGE warning (NOT cloned voice)", CallGuardRiskLevel.ORANGE, verdict.level)
        assertTrue("Alert must be active", verdict.isAlertActive)
        assertTrue(verdict.explanation.contains("BHIM UPI"))
        assertTrue(verdict.explanation.contains("pin, password"))
        assertTrue(verdict.explanation.contains("arrest, police"))
    }

    @Test
    fun testCase8_RealFriendSpeech_BhaiUpiKarDe_ReturnsOrange() {
        // Friend says "bhai UPI kar de" during active phone call with PhonePe open
        // Neither fraud credential terms nor voice clone detected
        val state = CallGuardState(
            callActive = true,
            upiForeground = true,
            currentPackage = "com.phonepe.app",
            fraudKeywordDetected = false,
            urgencyDetected = false,
            voiceCloneDetected = false
        )
        val verdict = riskEngine.evaluate(state)

        assertEquals("Active call + PhonePe with real friend talking naturally must remain ORANGE", CallGuardRiskLevel.ORANGE, verdict.level)
        assertTrue(verdict.isAlertActive)
        assertTrue(verdict.explanation.contains("PhonePe"))
        assertFalse("Must NOT claim cloned voice", verdict.explanation.contains("cloned", ignoreCase = true))
    }

    @Test
    fun testCase9_UnrelatedNotificationOrWindowEvent_NoAlert() {
        // WhatsApp or social media foregrounded during active call
        val state = CallGuardState(
            callActive = true,
            upiForeground = false,
            currentPackage = "com.whatsapp",
            fraudKeywordDetected = false,
            urgencyDetected = false
        )
        val verdict = riskEngine.evaluate(state)

        assertEquals(CallGuardRiskLevel.GREEN, verdict.level)
        assertFalse("Unrelated app or notification window event must never trigger alert", verdict.isAlertActive)

        // Even if credential words heard in ambient conversation during a non-banking call
        val ambientChatState = state.copy(
            fraudKeywordDetected = true,
            detectedFraudKeywords = listOf("password")
        )
        val ambientVerdict = riskEngine.evaluate(ambientChatState)
        assertEquals("Ambient chat without UPI app must remain GREEN", CallGuardRiskLevel.GREEN, ambientVerdict.level)
        assertFalse("Ambient chat without UPI app must never trigger alert overlay", ambientVerdict.isAlertActive)
    }

    @Test
    fun testKeywordsAlone_WithoutCallAndUpi_NeverTriggersRed() {
        val state = CallGuardState(
            callActive = false,
            upiForeground = false,
            fraudKeywordDetected = true,
            urgencyDetected = true,
            detectedFraudKeywords = listOf("otp", "pin"),
            detectedUrgencyKeywords = listOf("arrest")
        )
        val verdict = riskEngine.evaluate(state)

        assertEquals("Keywords alone without active call must stay GREEN", CallGuardRiskLevel.GREEN, verdict.level)
        assertFalse(verdict.isAlertActive)
    }

    @Test
    fun testVoiceId_CallActive_WithUpi_WithConfirmedSyntheticVoice_ReturnsRed() {
        val state = CallGuardState(
            callActive = true,
            upiForeground = true,
            currentPackage = "com.phonepe.app",
            voiceCloneDetected = true,
            voiceCloneConfidence = 0.94f
        )
        val verdict = riskEngine.evaluate(state)

        assertEquals("Call + UPI + Confirmed Synthetic Voice must trigger RED intervention", CallGuardRiskLevel.RED, verdict.level)
        assertTrue(verdict.isAlertActive)
        assertTrue(verdict.explanation.contains("PhonePe"))
        assertTrue(verdict.explanation.contains("cloned voice", ignoreCase = true))
        assertTrue(verdict.explanation.contains("94%"))
    }

    @Test
    fun testVoiceId_CallActive_WithUpi_WithGenuineVoice_ReturnsOrange() {
        // Genuine voice during Call + UPI does not trigger fraud intervention; remains elevated contextual warning
        val state = CallGuardState(
            callActive = true,
            upiForeground = true,
            currentPackage = "com.phonepe.app",
            voiceCloneDetected = false,
            voiceCloneConfidence = 0.0f
        )
        val verdict = riskEngine.evaluate(state)

        assertEquals("Call + UPI + Genuine voice must remain ORANGE", CallGuardRiskLevel.ORANGE, verdict.level)
        assertTrue(verdict.isAlertActive)
        assertTrue(verdict.explanation.contains("PhonePe"))
    }

    @Test
    fun testVoiceId_CallActive_WithUpi_WithUncertainVoice_ReturnsOrange() {
        val state = CallGuardState(
            callActive = true,
            upiForeground = true,
            currentPackage = "com.phonepe.app",
            voiceCloneDetected = false,
            voiceCloneConfidence = 0.50f
        )
        val verdict = riskEngine.evaluate(state)

        assertEquals("Call + UPI + Uncertain voice must fail safe and remain ORANGE", CallGuardRiskLevel.ORANGE, verdict.level)
        assertTrue(verdict.isAlertActive)
    }

    @Test
    fun testVoiceId_SyntheticVoice_WithoutCallOrUpi_ReturnsGreen() {
        val state = CallGuardState(
            callActive = false,
            upiForeground = false,
            voiceCloneDetected = true,
            voiceCloneConfidence = 0.98f
        )
        val verdict = riskEngine.evaluate(state)

        assertEquals("Synthetic voice detection without active call must stay GREEN", CallGuardRiskLevel.GREEN, verdict.level)
        assertFalse(verdict.isAlertActive)
    }

    @Test
    fun testVoiceId_SyntheticVoice_DuringCallWithoutUpi_ReturnsGreenMonitoring() {
        val state = CallGuardState(
            callActive = true,
            upiForeground = false,
            voiceCloneDetected = true,
            voiceCloneConfidence = 0.95f
        )
        val verdict = riskEngine.evaluate(state)

        assertEquals("Synthetic voice during call without UPI must stay GREEN ambient monitoring", CallGuardRiskLevel.GREEN, verdict.level)
        assertFalse(verdict.isAlertActive)
        assertTrue(verdict.explanation.contains("synthetic voice detected, but no payment app foregrounded"))
    }
}
