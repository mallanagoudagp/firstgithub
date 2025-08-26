package com.fraudguard.app.fusion

import android.content.Context
import android.util.Log
import com.fraudguard.app.core.AnomalyResult
import com.fraudguard.app.core.FraudAgent
import com.fraudguard.app.core.FusionResult
import com.fraudguard.app.core.ThreatLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.max
import kotlin.math.min

/**
 * FusionAgent combines anomaly scores from all fraud detection agents
 * Implements weighted fusion logic and decision engine with configurable thresholds
 */
class FusionAgent(
    private val context: Context,
    private val agents: List<FraudAgent>
) {
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val fusionResultFlow = MutableSharedFlow<FusionResult>()
    private var isActive = false
    
    // Fusion configuration
    private var fusionConfig = FusionConfig()
    private val recentResults = mutableMapOf<String, AnomalyResult>()
    private val fusionHistory = mutableListOf<FusionResult>()
    private val maxHistorySize = 100
    
    // Decision engine
    private val decisionEngine = DecisionEngine(context)
    
    data class FusionConfig(
        val lowThreshold: Float = 0.3f,
        val mediumThreshold: Float = 0.6f,
        val highThreshold: Float = 1.0f,
        val timeWindowMs: Long = 300000L, // 5 minutes
        val minAgentsRequired: Int = 2,
        val confidenceWeight: Float = 0.2f,
        val temporalWeight: Float = 0.1f,
        val adaptiveLearning: Boolean = true
    )
    
    /**
     * Start the fusion engine
     */
    suspend fun startFusion() {
        if (isActive) return
        
        isActive = true
        loadConfiguration()
        
        // Initialize all agents
        agents.forEach { agent ->
            try {
                if (agent.initialize()) {
                    agent.startMonitoring()
                    Log.d("FusionAgent", "Started agent: ${agent.agentId}")
                } else {
                    Log.w("FusionAgent", "Failed to initialize agent: ${agent.agentId}")
                }
            } catch (e: Exception) {
                Log.e("FusionAgent", "Error starting agent ${agent.agentId}: ${e.message}")
            }
        }
        
        // Start collecting and fusing results
        startFusionProcess()
    }
    
    /**
     * Stop the fusion engine
     */
    suspend fun stopFusion() {
        if (!isActive) return
        
        isActive = false
        
        // Stop all agents
        agents.forEach { agent ->
            try {
                agent.stopMonitoring()
                Log.d("FusionAgent", "Stopped agent: ${agent.agentId}")
            } catch (e: Exception) {
                Log.e("FusionAgent", "Error stopping agent ${agent.agentId}: ${e.message}")
            }
        }
        
        scope.cancel()
        recentResults.clear()
    }
    
    /**
     * Get the fusion result flow for real-time monitoring
     */
    fun getFusionResultFlow(): Flow<FusionResult> {
        return fusionResultFlow.asSharedFlow()
    }
    
    /**
     * Get current fusion result based on latest agent scores
     */
    suspend fun getCurrentFusionResult(): FusionResult {
        val currentScores = mutableMapOf<String, AnomalyResult>()
        
        // Collect current scores from all agents
        agents.forEach { agent ->
            try {
                val score = agent.getAnomalyScore()
                currentScores[agent.agentId] = AnomalyResult(
                    agentId = agent.agentId,
                    score = score,
                    timestamp = System.currentTimeMillis(),
                    confidence = 1.0f
                )
            } catch (e: Exception) {
                Log.e("FusionAgent", "Error getting score from ${agent.agentId}: ${e.message}")
            }
        }
        
        return performFusion(currentScores)
    }
    
    /**
     * Start the fusion process that continuously monitors agent results
     */
    private fun startFusionProcess() {
        scope.launch {
            // Combine all agent flows
            val agentFlows = agents.map { agent ->
                agent.getAnomalyScoreFlow()
                    .catch { e -> 
                        Log.e("FusionAgent", "Error in agent ${agent.agentId} flow: ${e.message}")
                    }
            }
            
            // Merge all flows and process results
            merge(*agentFlows.toTypedArray())
                .buffer(capacity = 50)
                .collect { result ->
                    try {
                        processAgentResult(result)
                    } catch (e: Exception) {
                        Log.e("FusionAgent", "Error processing agent result: ${e.message}")
                    }
                }
        }
        
        // Periodic fusion evaluation
        scope.launch {
            while (isActive) {
                try {
                    evaluateAndEmitFusion()
                    delay(5000) // Evaluate every 5 seconds
                } catch (e: Exception) {
                    Log.e("FusionAgent", "Error in periodic fusion evaluation: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Process individual agent result
     */
    private suspend fun processAgentResult(result: AnomalyResult) {
        // Update recent results
        recentResults[result.agentId] = result
        
        // Clean up old results
        val currentTime = System.currentTimeMillis()
        recentResults.entries.removeAll { (_, anomalyResult) ->
            currentTime - anomalyResult.timestamp > fusionConfig.timeWindowMs
        }
        
        // Trigger fusion if we have enough recent results
        if (recentResults.size >= fusionConfig.minAgentsRequired) {
            evaluateAndEmitFusion()
        }
    }
    
    /**
     * Evaluate current state and emit fusion result
     */
    private suspend fun evaluateAndEmitFusion() {
        if (recentResults.size < fusionConfig.minAgentsRequired) return
        
        val fusionResult = performFusion(recentResults.toMap())
        
        // Add to history
        fusionHistory.add(fusionResult)
        if (fusionHistory.size > maxHistorySize) {
            fusionHistory.removeAt(0)
        }
        
        // Emit result
        fusionResultFlow.emit(fusionResult)
        
        // Execute decision
        decisionEngine.executeDecision(fusionResult)
        
        // Adaptive learning
        if (fusionConfig.adaptiveLearning) {
            updateFusionWeights(fusionResult)
        }
    }
    
    /**
     * Perform weighted fusion of agent scores
     */
    private fun performFusion(agentScores: Map<String, AnomalyResult>): FusionResult {
        if (agentScores.isEmpty()) {
            return createEmptyFusionResult()
        }
        
        // Calculate weighted score
        var totalWeightedScore = 0f
        var totalWeight = 0f
        var totalConfidenceWeight = 0f
        
        agentScores.forEach { (agentId, result) ->
            val agent = agents.find { it.agentId == agentId }
            if (agent != null) {
                val weight = agent.weight
                val confidenceAdjustedWeight = weight * result.confidence
                
                totalWeightedScore += result.score * confidenceAdjustedWeight
                totalWeight += confidenceAdjustedWeight
                totalConfidenceWeight += result.confidence * weight
            }
        }
        
        val baseScore = if (totalWeight > 0) totalWeightedScore / totalWeight else 0f
        
        // Apply temporal weighting (recent results have higher impact)
        val temporalScore = applyTemporalWeighting(agentScores, baseScore)
        
        // Apply confidence weighting
        val avgConfidence = if (totalWeight > 0) totalConfidenceWeight / totalWeight else 0f
        val finalScore = (temporalScore * (1 - fusionConfig.confidenceWeight) + 
                         temporalScore * avgConfidence * fusionConfig.confidenceWeight)
            .coerceIn(0f, 1f)
        
        // Determine threat level
        val threatLevel = determineThreatLevel(finalScore)
        
        // Generate recommended action
        val recommendedAction = generateRecommendedAction(threatLevel, agentScores)
        
        return FusionResult(
            overallScore = finalScore,
            threatLevel = threatLevel,
            agentScores = agentScores,
            timestamp = System.currentTimeMillis(),
            recommendedAction = recommendedAction
        )
    }
    
    /**
     * Apply temporal weighting to give more importance to recent results
     */
    private fun applyTemporalWeighting(agentScores: Map<String, AnomalyResult>, baseScore: Float): Float {
        if (fusionConfig.temporalWeight <= 0) return baseScore
        
        val currentTime = System.currentTimeMillis()
        var temporalWeightedScore = 0f
        var totalTemporalWeight = 0f
        
        agentScores.forEach { (agentId, result) ->
            val age = currentTime - result.timestamp
            val temporalWeight = max(0f, 1f - (age.toFloat() / fusionConfig.timeWindowMs))
            val agent = agents.find { it.agentId == agentId }
            
            if (agent != null) {
                val combinedWeight = agent.weight * temporalWeight
                temporalWeightedScore += result.score * combinedWeight
                totalTemporalWeight += combinedWeight
            }
        }
        
        val temporalScore = if (totalTemporalWeight > 0) temporalWeightedScore / totalTemporalWeight else baseScore
        
        return baseScore * (1 - fusionConfig.temporalWeight) + temporalScore * fusionConfig.temporalWeight
    }
    
    /**
     * Determine threat level based on score and thresholds
     */
    private fun determineThreatLevel(score: Float): ThreatLevel {
        return when {
            score >= fusionConfig.highThreshold -> ThreatLevel.HIGH
            score >= fusionConfig.mediumThreshold -> ThreatLevel.MEDIUM
            score >= fusionConfig.lowThreshold -> ThreatLevel.LOW
            else -> ThreatLevel.LOW
        }
    }
    
    /**
     * Generate recommended action based on threat level and contributing agents
     */
    private fun generateRecommendedAction(
        threatLevel: ThreatLevel, 
        agentScores: Map<String, AnomalyResult>
    ): String {
        val highScoreAgents = agentScores.filter { it.value.score > 0.7f }.keys
        
        return when (threatLevel) {
            ThreatLevel.LOW -> {
                "Log activity for monitoring. Contributing agents: ${agentScores.keys.joinToString(", ")}"
            }
            ThreatLevel.MEDIUM -> {
                val action = if (highScoreAgents.contains("TouchAgent") || highScoreAgents.contains("TypingAgent")) {
                    "Require biometric re-authentication due to behavioral anomalies"
                } else if (highScoreAgents.contains("HoneypotAgent")) {
                    "Require additional verification due to suspicious network activity"
                } else {
                    "Require standard re-authentication"
                }
                "$action. High-risk agents: ${highScoreAgents.joinToString(", ")}"
            }
            ThreatLevel.HIGH -> {
                val lockReason = when {
                    highScoreAgents.contains("HoneypotAgent") -> "automated attack detected"
                    highScoreAgents.contains("MovementAgent") -> "location spoofing detected"
                    highScoreAgents.size >= 3 -> "multiple behavioral anomalies detected"
                    else -> "high-risk activity detected"
                }
                "Lock account immediately - $lockReason. Affected agents: ${highScoreAgents.joinToString(", ")}"
            }
        }
    }
    
    /**
     * Update fusion weights based on historical performance (adaptive learning)
     */
    private fun updateFusionWeights(result: FusionResult) {
        // This is a simplified adaptive learning mechanism
        // In a real implementation, you would use more sophisticated ML techniques
        
        if (fusionHistory.size < 10) return
        
        val recentHistory = fusionHistory.takeLast(10)
        val highThreatCount = recentHistory.count { it.threatLevel == ThreatLevel.HIGH }
        
        // If we're seeing too many high threats, we might need to adjust thresholds
        if (highThreatCount > 7) {
            fusionConfig = fusionConfig.copy(
                highThreshold = min(1.0f, fusionConfig.highThreshold + 0.05f),
                mediumThreshold = min(fusionConfig.highThreshold - 0.1f, fusionConfig.mediumThreshold + 0.03f)
            )
        } else if (highThreatCount < 1) {
            // If we're not seeing enough threats, lower thresholds slightly
            fusionConfig = fusionConfig.copy(
                highThreshold = max(0.7f, fusionConfig.highThreshold - 0.02f),
                mediumThreshold = max(0.4f, fusionConfig.mediumThreshold - 0.01f)
            )
        }
    }
    
    /**
     * Create empty fusion result for when no agents are available
     */
    private fun createEmptyFusionResult(): FusionResult {
        return FusionResult(
            overallScore = 0f,
            threatLevel = ThreatLevel.LOW,
            agentScores = emptyMap(),
            timestamp = System.currentTimeMillis(),
            recommendedAction = "No agents active - monitoring disabled"
        )
    }
    
    /**
     * Load configuration from preferences
     */
    private fun loadConfiguration() {
        val prefs = context.getSharedPreferences("fusion_config", Context.MODE_PRIVATE)
        fusionConfig = FusionConfig(
            lowThreshold = prefs.getFloat("low_threshold", 0.3f),
            mediumThreshold = prefs.getFloat("medium_threshold", 0.6f),
            highThreshold = prefs.getFloat("high_threshold", 1.0f),
            timeWindowMs = prefs.getLong("time_window_ms", 300000L),
            minAgentsRequired = prefs.getInt("min_agents_required", 2),
            confidenceWeight = prefs.getFloat("confidence_weight", 0.2f),
            temporalWeight = prefs.getFloat("temporal_weight", 0.1f),
            adaptiveLearning = prefs.getBoolean("adaptive_learning", true)
        )
    }
    
    /**
     * Save configuration to preferences
     */
    fun saveConfiguration() {
        val prefs = context.getSharedPreferences("fusion_config", Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat("low_threshold", fusionConfig.lowThreshold)
            .putFloat("medium_threshold", fusionConfig.mediumThreshold)
            .putFloat("high_threshold", fusionConfig.highThreshold)
            .putLong("time_window_ms", fusionConfig.timeWindowMs)
            .putInt("min_agents_required", fusionConfig.minAgentsRequired)
            .putFloat("confidence_weight", fusionConfig.confidenceWeight)
            .putFloat("temporal_weight", fusionConfig.temporalWeight)
            .putBoolean("adaptive_learning", fusionConfig.adaptiveLearning)
            .apply()
    }
    
    /**
     * Update fusion configuration
     */
    fun updateConfiguration(newConfig: FusionConfig) {
        fusionConfig = newConfig
        saveConfiguration()
    }
    
    /**
     * Get current configuration
     */
    fun getConfiguration(): FusionConfig {
        return fusionConfig
    }
    
    /**
     * Get fusion statistics
     */
    fun getFusionStats(): Map<String, Any> {
        val recentFusions = fusionHistory.filter { 
            System.currentTimeMillis() - it.timestamp < 3600000 // Last hour
        }
        
        return mapOf(
            "total_fusions" to fusionHistory.size,
            "recent_fusions" to recentFusions.size,
            "active_agents" to agents.count { it.isActive },
            "current_threat_level" to (fusionHistory.lastOrNull()?.threatLevel?.name ?: "UNKNOWN"),
            "average_score" to (recentFusions.map { it.overallScore }.average().takeIf { !it.isNaN() } ?: 0.0),
            "threat_distribution" to recentFusions.groupBy { it.threatLevel }.mapValues { it.value.size },
            "agent_contributions" to recentResults.mapValues { it.value.score },
            "configuration" to mapOf(
                "low_threshold" to fusionConfig.lowThreshold,
                "medium_threshold" to fusionConfig.mediumThreshold,
                "high_threshold" to fusionConfig.highThreshold,
                "adaptive_learning" to fusionConfig.adaptiveLearning
            )
        )
    }
    
    /**
     * Force a fusion evaluation (useful for testing)
     */
    suspend fun forceFusionEvaluation(): FusionResult {
        return getCurrentFusionResult()
    }
    
    /**
     * Get recent fusion history
     */
    fun getFusionHistory(limit: Int = 50): List<FusionResult> {
        return fusionHistory.takeLast(limit)
    }
}