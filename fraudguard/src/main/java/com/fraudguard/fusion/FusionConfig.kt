package com.fraudguard.fusion

/**
 * Configuration for fusion weights and thresholds.
 *
 * - agentWeights: mapping from agent name to weight (non-negative). Missing agents default to 1.0.
 * - lowThreshold: below this, decision is LOG.
 * - highThreshold: at or above this, decision is LOCK. Between low and high is REAUTH.
 * - normalizeWeights: when true, weights are normalized to sum to 1 before scoring.
 */
data class FusionConfig(
    val agentWeights: Map<String, Double> = mapOf(
        "TouchAgent" to 0.2,
        "TypingAgent" to 0.2,
        "UsageAgent" to 0.2,
        "MovementAgent" to 0.2,
        "HoneypotAgent" to 0.2
    ),
    val lowThreshold: Double = 0.3,
    val highThreshold: Double = 0.7,
    val normalizeWeights: Boolean = true
) {
    init {
        require(lowThreshold in 0.0..1.0 && highThreshold in 0.0..1.0) {
            "Thresholds must be within [0.0, 1.0]"
        }
        require(lowThreshold < highThreshold) { "lowThreshold must be < highThreshold" }
        require(agentWeights.values.all { it >= 0.0 }) { "Weights must be non-negative" }
    }
}