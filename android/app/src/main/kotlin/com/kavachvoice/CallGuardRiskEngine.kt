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
 * - call + UPI + fraud signal    -> RED (Direct scam intervention)
 * - call + UPI + urgency only    -> ORANGE (Elevated risk context)
 * - call + UPI + fraud + urgency -> RED (High-confidence scam intervention)
 *
 * Rule: Keyword or urgency signal alone (without active call AND UPI) NEVER produces RED.
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
        // VoiceID Real Inference: Call + UPI + Confirmed Synthetic Voice -> RED
        if (state.voiceCloneDetected) {
            val pct = (state.voiceCloneConfidence * 100).toInt()
            val explanation = "Active call + $appName opened + Synthetic/Cloned Voice Detected (VoiceID RawNet2: $pct%)"
            return CallGuardVerdict(
                level = CallGuardRiskLevel.RED,
                explanation = explanation,
                isAlertActive = true
            )
        }

        // Cases 5 & 7: Call + UPI + Fraud Keyword -> RED
        if (state.fraudKeywordDetected) {
            val fraudTerms = if (state.detectedFraudKeywords.isNotEmpty()) {
                state.detectedFraudKeywords.joinToString(", ")
            } else {
                "OTP/PIN"
            }

            val explanation = if (state.urgencyDetected) {
                val urgencyTerms = if (state.detectedUrgencyKeywords.isNotEmpty()) {
                    state.detectedUrgencyKeywords.joinToString(", ")
                } else {
                    "Coercive urgency"
                }
                "Active call + $appName opened + OTP/credential request ($fraudTerms) + Urgency detected ($urgencyTerms)"
            } else {
                "Active call + $appName opened + OTP/credential request detected ($fraudTerms)"
            }

            return CallGuardVerdict(
                level = CallGuardRiskLevel.RED,
                explanation = explanation,
                isAlertActive = true
            )
        }

        // Cases 4 & 6: Call + UPI (with or without urgency only) -> ORANGE
        val explanation = if (state.urgencyDetected) {
            val urgencyTerms = if (state.detectedUrgencyKeywords.isNotEmpty()) {
                state.detectedUrgencyKeywords.joinToString(", ")
            } else {
                "Urgency pressure"
            }
            "Active call + $appName opened + Urgency detected ($urgencyTerms) — Verify caller identity"
        } else {
            "Active call + $appName opened in foreground — High-risk financial context"
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
