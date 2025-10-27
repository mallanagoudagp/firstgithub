package com.fraudguard.agents

import com.fraudguard.core.FraudAgent
import com.fraudguard.core.model.AgentContext
import com.fraudguard.core.model.AgentResult

class HoneypotAgent(private val honeypotPaths: Set<String> = setOf("/api/secret/admin", "/api/debug/flags")) : FraudAgent {
    override val name: String = "HoneypotAgent"

    override suspend fun evaluate(context: AgentContext): AgentResult {
        val requestedUrl = context.request?.url ?: return AgentResult(name, 0.0, mapOf("reason" to "no_request"))
        val hit = honeypotPaths.any { requestedUrl.contains(it, ignoreCase = true) }
        val score = if (hit) 1.0 else 0.0
        return AgentResult(
            agentName = name,
            score = score,
            details = mapOf(
                "url" to requestedUrl,
                "honeypotHit" to hit.toString()
            )
        )
    }
}