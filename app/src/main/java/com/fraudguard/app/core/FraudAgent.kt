package com.fraudguard.app.core

import kotlinx.coroutines.flow.Flow

/**
 * Base interface for all fraud detection agents
 * Each agent outputs a normalized anomaly score between 0.0 (normal) and 1.0 (highly anomalous)
 */
interface FraudAgent {
    
    /**
     * Unique identifier for this agent
     */
    val agentId: String
    
    /**
     * Weight factor for this agent in the fusion process
     */
    val weight: Float
    
    /**
     * Initialize the agent with necessary resources
     */
    suspend fun initialize(): Boolean
    
    /**
     * Start monitoring and collecting data
     */
    suspend fun startMonitoring()
    
    /**
     * Stop monitoring and release resources
     */
    suspend fun stopMonitoring()
    
    /**
     * Get the current anomaly score
     * @return Float value between 0.0 (normal) and 1.0 (highly anomalous)
     */
    suspend fun getAnomalyScore(): Float
    
    /**
     * Flow of real-time anomaly scores
     */
    fun getAnomalyScoreFlow(): Flow<AnomalyResult>
    
    /**
     * Check if the agent is currently active
     */
    val isActive: Boolean
}

/**
 * Data class representing an anomaly detection result
 */
data class AnomalyResult(
    val agentId: String,
    val score: Float,
    val timestamp: Long,
    val details: Map<String, Any> = emptyMap(),
    val confidence: Float = 1.0f
)

/**
 * Enum representing different threat levels based on anomaly scores
 */
enum class ThreatLevel(val threshold: Float, val description: String) {
    LOW(0.3f, "Normal behavior - log only"),
    MEDIUM(0.6f, "Suspicious behavior - require re-authentication"),
    HIGH(1.0f, "Highly anomalous - lock account")
}

/**
 * Data class for fusion result containing all agent scores
 */
data class FusionResult(
    val overallScore: Float,
    val threatLevel: ThreatLevel,
    val agentScores: Map<String, AnomalyResult>,
    val timestamp: Long,
    val recommendedAction: String
)