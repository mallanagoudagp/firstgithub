package com.fraudguard.fusion

import com.fraudguard.core.FraudAgent
import com.fraudguard.core.model.AgentContext
import com.fraudguard.core.model.AgentResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class FusionAgent(
    private val agents: List<FraudAgent>,
    private val config: FusionConfig = FusionConfig()
) {
    suspend fun evaluate(context: AgentContext): FusionOutcome = coroutineScope {
        val results: List<AgentResult> = agents.map { agent ->
            async { runCatching { agent.evaluate(context) }.getOrElse { AgentResult(agent.name, 0.0, mapOf("error" to (it.message ?: "unknown"))) } }
        }.map { it.await() }

        val (weights, normalized) = buildWeights(config)
        val scoreByAgent = results.associate { it.agentName to it.score }
        val weightedSum = results.sumOf { (weights[it.agentName] ?: 1.0) * it.score }
        val weightTotal = if (normalized) 1.0 else weights.values.sum().takeIf { it > 0.0 } ?: results.size.toDouble()
        val totalScore = (weightedSum / weightTotal).coerceIn(0.0, 1.0)

        val decision = when {
            totalScore >= config.highThreshold -> Decision.LOCK
            totalScore >= config.lowThreshold -> Decision.REAUTH
            else -> Decision.LOG
        }

        FusionOutcome(
            decision = decision,
            totalScore = totalScore,
            agentScores = scoreByAgent,
            rationale = "normalized=$normalized, low=${config.lowThreshold}, high=${config.highThreshold}"
        )
    }

    private fun buildWeights(config: FusionConfig): Pair<Map<String, Double>, Boolean> {
        val weights = config.agentWeights.ifEmpty { emptyMap() }
        if (!config.normalizeWeights) return weights to false
        val sum = weights.values.sum().takeIf { it > 0 } ?: 1.0
        val normalized = weights.mapValues { (_, w) -> w / sum }
        return normalized to true
    }
}