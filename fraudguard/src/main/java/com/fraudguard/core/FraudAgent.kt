package com.fraudguard.core

import com.fraudguard.core.model.AgentContext
import com.fraudguard.core.model.AgentResult

/**
 * Contract for all FraudGuard agents. Each agent analyzes a specific signal surface
 * and outputs an anomaly score in [0.0, 1.0].
 */
interface FraudAgent {
    /** Stable, human-readable unique name used for weights and logging. */
    val name: String

    /**
     * Perform analysis and return an anomaly score in [0.0, 1.0].
     */
    suspend fun evaluate(context: AgentContext): AgentResult
}