package com.fraudguard

import com.fraudguard.agents.HoneypotAgent
import com.fraudguard.agents.MovementAgent
import com.fraudguard.agents.TouchAgent
import com.fraudguard.agents.TypingAgent
import com.fraudguard.agents.UsageAgent
import com.fraudguard.core.FraudAgent
import com.fraudguard.core.model.AgentContext
import com.fraudguard.fusion.FusionAgent
import com.fraudguard.fusion.FusionConfig
import com.fraudguard.fusion.FusionOutcome

class FraudGuard(
    private val fusionConfig: FusionConfig = FusionConfig(),
    agents: List<FraudAgent>? = null
) {
    private val agents: List<FraudAgent> = agents ?: listOf(
        TouchAgent(),
        TypingAgent(),
        UsageAgent(),
        MovementAgent(),
        HoneypotAgent()
    )

    private val fusionAgent: FusionAgent = FusionAgent(this.agents, fusionConfig)

    suspend fun evaluate(context: AgentContext): FusionOutcome {
        return fusionAgent.evaluate(context)
    }
}