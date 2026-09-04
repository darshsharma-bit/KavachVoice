package com.kavachvoice

/**
 * CallGuard Risk Level enum for state evaluation.
 */
enum class CallGuardRiskLevel {
    GREEN,
    ORANGE,
    RED
}

/**
 * Multi-signal CallGuard state data model.
 *
 * @param callActive Whether a cellular or VoIP call is active
 * @param upiForeground Whether a recognized UPI payment app is in foreground
 * @param currentPackage Package name of current foreground app
 * @param fraudKeywordDetected Whether credential theft keywords (OTP, PIN, Password) were signaled
 * @param urgencyDetected Whether coercive pressure keywords (Jaldi, Arrest, Police) were signaled
 * @param detectedFraudKeywords List of detected fraud keyword tokens
 * @param detectedUrgencyKeywords List of detected urgency keyword tokens
 */
data class CallGuardState(
    val callActive: Boolean = false,
    val upiForeground: Boolean = false,
    val currentPackage: String? = null,
    val fraudKeywordDetected: Boolean = false,
    val urgencyDetected: Boolean = false,
    val detectedFraudKeywords: List<String> = emptyList(),
    val detectedUrgencyKeywords: List<String> = emptyList(),
    val voiceCloneDetected: Boolean = false,
    val voiceCloneConfidence: Float = 0.0f,
)

/**
 * Evaluated verdict with an explainable audit rationale.
 */
data class CallGuardVerdict(
    val level: CallGuardRiskLevel,
    val explanation: String,
    val isAlertActive: Boolean,
)

/**
 * Explainable Multi-Signal Risk Engine for CallGuard (Layer 2).
 *
 * Strict Deterministic Truth Table:
 * - no call + no UPI             -> GREEN (Safe)
 * - no call + UPI                -> GREEN (Benign payment)
 * - call + no UPI                -> GREEN / MONITORING (Ambient observation)
 * - call + UPI                   -> ORANGE (Elevated risk context)
 * - call + UPI + fraud signal    -> ORANGE
 *   (Credential warning; keywords alone never trigger RED)
 *
 * - call + UPI + urgency only    -> ORANGE (Elevated risk context)
 *
 * - call + UPI + fraud + urgency -> ORANGE
 *   (Urgent credential warning; keywords alone never trigger RED)
 *
 * - call + UPI + confirmed clone -> RED
 *   (High-confidence synthetic-voice intervention)
 *
 * Rule: Only confirmed synthetic voice during Call + UPI escalates to RED. Keywords/urgency alone NEVER produce RED.
 * Every ORANGE/RED alert provides a truthful explanation derived strictly from active signals.
 */
class CallGuardRiskEngine(
    private val knownUpiPackages: Map<String, String> = DEFAULT_UPI_PACKAGES
) {
    companion object {
        val DEFAULT_UPI_PACKAGES = mapOf(
            "com.google.android.apps.nbu.paisa.user" to "Google Pay",
            "net.one97.paytm" to "Paytm",
            "com.phonepe.app" to "PhonePe",
            "in.org.npci.upiapp" to "BHIM UPI",
            "com.amazon.mShop.android.shopping" to "Amazon Pay",
        )

        val FRAUD_KEYWORDS = setOf(
            "otp", "ओटीपी", "pin", "पिन", "password", "पासवर्ड",
            "cvv", "सीवीवी", "card number", "कार्ड नंबर",
            "share karo", "batao", "बताइए", "बताओ"
        )

        val URGENCY_KEYWORDS = setOf(
            "account band", "अकाउंट बंद", "block", "ब्लॉक",
            "arrest", "गिरफ्तार", "police", "पुलिस",
            "abhi", "अभी", "turant", "तुरंत", "jaldi", "जल्दी",
            "fir", "cyber crime", "साइबर क्राइम", "penalty", "जुर्माना"
        )
    }

    /**
     * Evaluates state deterministically against ratified multi-signal matrix.
     */
    fun evaluate(state: CallGuardState): CallGuardVerdict {
        val appName = state.currentPackage?.let { knownUpiPackages[it] ?: it } ?: "Payment App"

        // Case 1 & 2: No active call -> GREEN
        if (!state.callActive) {
            return CallGuardVerdict(
                level = CallGuardRiskLevel.GREEN,
                explanation = if (state.upiForeground) {
                    "$appName in foreground (Normal payment usage — no active phone call)"
                } else {
                    "System secure — No active call, no financial app"
                },
                isAlertActive = false
            )
        }

        // Case 3: Call active, but no UPI app in foreground -> GREEN / MONITORING
        // Keywords, urgency, or synthetic voice alone during call WITHOUT UPI never triggers RED or ORANGE overlay
        if (!state.upiForeground) {
            val note = when {
                state.voiceCloneDetected ->
                    " [Signal noted: synthetic voice detected, but no payment app foregrounded]"
                state.fraudKeywordDetected && state.urgencyDetected ->
                    " [Signal noted: credential terms and urgency detected, but no payment app foregrounded]"
                state.fraudKeywordDetected ->
                    " [Signal noted: credential terms detected, but no payment app foregrounded]"
                state.urgencyDetected ->
                    " [Signal noted: urgency terms detected, but no payment app foregrounded]"
                else -> ""
            }
            return CallGuardVerdict(
                level = CallGuardRiskLevel.GREEN,
                explanation = "Active call in progress — Monitoring background audio for fraud context$note",
                isAlertActive = false
            )
        }

        // Active Call + UPI App foregrounded:
        // Critical Rule: RED is strictly reserved for confirmed high-confidence synthetic voice.
        // Financial language, OTP keywords, and urgency are language signals, NOT proof of voice cloning.
        if (state.voiceCloneDetected) {
            val pct = (state.voiceCloneConfidence * 100).toInt()
            val explanation = "Possible cloned voice detected during payment call ($appName, VoiceID: $pct%)"
            return CallGuardVerdict(
                level = CallGuardRiskLevel.RED,
                explanation = explanation,
                isAlertActive = true
            )
        }

        // Cases: Call + UPI (with or without keywords/urgency) -> ORANGE (Contextual Risk Warning)
        // Genuine voice, uncertain voice, or suspicious language all remain ORANGE.
        val explanation = when {
            state.fraudKeywordDetected && state.urgencyDetected -> {
                val fTerms = state.detectedFraudKeywords.joinToString(", ").ifEmpty { "OTP/PIN" }
                val uTerms = state.detectedUrgencyKeywords.joinToString(", ").ifEmpty { "Urgency" }
                "Payment app open ($appName) + Credential request ($fTerms) + Urgency ($uTerms) — Take a moment before transferring money"
            }
            state.fraudKeywordDetected -> {
                val fTerms = state.detectedFraudKeywords.joinToString(", ").ifEmpty { "OTP/PIN" }
                "Payment app open ($appName) + Credential request detected ($fTerms) — Do not share passwords or PINs"
            }
            state.urgencyDetected -> {
                val uTerms = state.detectedUrgencyKeywords.joinToString(", ").ifEmpty { "Urgency" }
                "Payment app open ($appName) + Urgency detected ($uTerms) — Verify caller identity"
            }
            else -> {
                "Active call + $appName open in foreground — High-risk financial context"
            }
        }

        return CallGuardVerdict(
            level = CallGuardRiskLevel.ORANGE,
            explanation = explanation,
            isAlertActive = true
        )
    }

    /**
     * Categorizes a raw list of detected keyword tokens into fraud vs urgency sets.
     */
    fun categorizeKeywords(keywords: List<String>): Pair<List<String>, List<String>> {
        val fraudList = mutableListOf<String>()
        val urgencyList = mutableListOf<String>()

        for (kw in keywords) {
            val normalized = kw.trim().lowercase()
            if (FRAUD_KEYWORDS.any { normalized.contains(it) || it.contains(normalized) }) {
                fraudList.add(kw)
            }
            if (URGENCY_KEYWORDS.any { normalized.contains(it) || it.contains(normalized) }) {
                urgencyList.add(kw)
            }
        }

        return Pair(fraudList, urgencyList)
    }
}
