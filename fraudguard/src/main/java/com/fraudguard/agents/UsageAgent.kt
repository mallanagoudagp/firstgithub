package com.fraudguard.agents

import com.fraudguard.core.FraudAgent
import com.fraudguard.core.model.AgentContext
import com.fraudguard.core.model.AgentResult

class UsageAgent : FraudAgent {
    override val name: String = "UsageAgent"

    override suspend fun evaluate(context: AgentContext): AgentResult {
        val events = context.appUsageEvents.orEmpty()
        if (events.isEmpty()) return AgentResult(name, 0.0, mapOf("reason" to "no_usage_events"))

        val totalDuration = events.sumOf { it.durationMs }
        val backgroundFraction = if (totalDuration > 0) {
            events.filter { it.inBackground }.sumOf { it.durationMs }.toDouble() / totalDuration
        } else 0.0

        val longSessionPenalty = (totalDuration / 30_000.0).coerceIn(0.0, 1.0) // >30s becomes suspicious
        val bgPenalty = backgroundFraction.coerceIn(0.0, 1.0)
        val score = (0.6 * longSessionPenalty + 0.4 * bgPenalty).coerceIn(0.0, 1.0)

        return AgentResult(
            agentName = name,
            score = score,
            details = mapOf(
                "totalDurationMs" to totalDuration.toString(),
                "backgroundFraction" to backgroundFraction.toString()
            )
        )
    }
}