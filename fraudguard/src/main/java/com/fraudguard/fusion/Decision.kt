package com.fraudguard.fusion

/**
 * Decision levels derived from the fused anomaly score.
 */
enum class Decision {
    LOG,
    REAUTH,
    LOCK
}

/**
 * Outcome from FusionAgent, capturing the decision, total score, and individual scores.
 */
data class FusionOutcome(
    val decision: Decision,
    val totalScore: Double,
    val agentScores: Map<String, Double>,
    val rationale: String? = null
)