package com.fraudguard.core.model

/**
 * Result from an agent evaluation.
 */
data class AgentResult(
    val agentName: String,
    val score: Double,
    val details: Map<String, String> = emptyMap()
) {
    init {
        require(score in 0.0..1.0) { "Score must be in [0.0, 1.0], was $score" }
    }
}