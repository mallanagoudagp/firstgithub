package com.fraudguard.agents

import com.fraudguard.core.FraudAgent
import com.fraudguard.core.model.AgentContext
import com.fraudguard.core.model.AgentResult
import kotlin.math.abs

class TypingAgent : FraudAgent {
    override val name: String = "TypingAgent"

    override suspend fun evaluate(context: AgentContext): AgentResult {
        val events = context.typingEvents.orEmpty().sortedBy { it.timestampMs }
        if (events.isEmpty()) return AgentResult(name, 0.0, mapOf("reason" to "no_typing_events"))

        val intervals = events.windowed(2).mapNotNull { (a, b) ->
            if (a.isKeyDown && b.isKeyDown) b.timestampMs - a.timestampMs else null
        }
        if (intervals.isEmpty()) return AgentResult(name, 0.0, mapOf("reason" to "insufficient_intervals"))

        val avgInterval = intervals.average()
        val jitter = intervals.map { abs(it - avgInterval) }.average()
        val score = (jitter / (avgInterval + 1e-3)).coerceIn(0.0, 1.0)

        return AgentResult(
            agentName = name,
            score = score,
            details = mapOf(
                "avgIntervalMs" to avgInterval.toString(),
                "jitterMs" to jitter.toString()
            )
        )
    }
}