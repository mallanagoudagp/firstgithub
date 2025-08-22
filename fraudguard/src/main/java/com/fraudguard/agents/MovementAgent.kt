package com.fraudguard.agents

import com.fraudguard.core.FraudAgent
import com.fraudguard.core.model.AgentContext
import com.fraudguard.core.model.AgentResult
import kotlin.math.abs

class MovementAgent : FraudAgent {
    override val name: String = "MovementAgent"

    override suspend fun evaluate(context: AgentContext): AgentResult {
        val samples = context.movementSamples.orEmpty()
        if (samples.isEmpty()) return AgentResult(name, 0.0, mapOf("reason" to "no_movement_samples"))

        val avgX = samples.map { it.accelX }.average()
        val avgY = samples.map { it.accelY }.average()
        val avgZ = samples.map { it.accelZ }.average()
        val varX = samples.map { abs(it.accelX - avgX) }.average()
        val varY = samples.map { abs(it.accelY - avgY) }.average()
        val varZ = samples.map { abs(it.accelZ - avgZ) }.average()
        val motionVar = (varX + varY + varZ) / 3.0

        val avgSpeed = samples.mapNotNull { it.speedMps }.average().takeIf { !it.isNaN() } ?: 0.0
        val speedPenalty = (avgSpeed / 10.0).coerceIn(0.0, 1.0) // moving fast while using app
        val varPenalty = (motionVar / 5.0).coerceIn(0.0, 1.0)
        val score = (0.5 * speedPenalty + 0.5 * varPenalty).coerceIn(0.0, 1.0)

        return AgentResult(
            agentName = name,
            score = score,
            details = mapOf(
                "avgSpeedMps" to avgSpeed.toString(),
                "motionVar" to motionVar.toString()
            )
        )
    }
}