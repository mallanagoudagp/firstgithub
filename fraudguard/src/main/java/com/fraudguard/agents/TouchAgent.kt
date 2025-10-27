package com.fraudguard.agents

import com.fraudguard.core.FraudAgent
import com.fraudguard.core.model.AgentContext
import com.fraudguard.core.model.AgentResult
import kotlin.math.abs

class TouchAgent : FraudAgent {
    override val name: String = "TouchAgent"

    override suspend fun evaluate(context: AgentContext): AgentResult {
        val events = context.touchEvents.orEmpty()
        if (events.isEmpty()) return AgentResult(name, 0.0, mapOf("reason" to "no_touch_events"))

        val avgPressure = events.map { it.pressure }.average()
        val avgVelocity = events.map { it.velocity }.average()
        val pressureVar = events.map { abs(it.pressure - avgPressure) }.average()
        val velocityVar = events.map { abs(it.velocity - avgVelocity) }.average()

        val pressureScore = (pressureVar / (avgPressure + 1e-3)).coerceIn(0.0, 1.0)
        val velocityScore = (velocityVar / (avgVelocity + 1e-3)).coerceIn(0.0, 1.0)
        val score = (pressureScore + velocityScore) / 2.0

        return AgentResult(
            agentName = name,
            score = score,
            details = mapOf(
                "avgPressure" to avgPressure.toString(),
                "avgVelocity" to avgVelocity.toString()
            )
        )
    }
}