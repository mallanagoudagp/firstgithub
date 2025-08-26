package com.fraudguard.app.agents

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.fraudguard.app.core.AnomalyResult
import com.fraudguard.app.core.FraudAgent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.*
import kotlin.math.abs

/**
 * UsageAgent monitors app usage patterns and behavior to detect anomalies
 * Analyzes session duration, navigation patterns, feature usage, and timing
 */
class UsageAgent(
    private val context: Context,
    override val weight: Float = 0.15f
) : FraudAgent {
    
    override val agentId: String = "UsageAgent"
    override var isActive: Boolean = false
        private set
    
    private val anomalyFlow = MutableSharedFlow<AnomalyResult>()
    
    // Usage tracking data
    private val sessionEvents = mutableListOf<SessionEvent>()
    private val navigationEvents = mutableListOf<NavigationEvent>()
    private val featureUsage = mutableMapOf<String, Int>()
    private val maxEvents = 100
    
    // Session tracking
    private var sessionStartTime: Long = 0
    private var currentActivity: String = ""
    private var activityStartTime: Long = 0
    
    // Baseline metrics
    private var baselineSessionDuration = 300000L // 5 minutes
    private var baselineActivitiesPerSession = 5
    private var baselineFeatureUsageRate = 0.1f // Features per minute
    private var baselineNavigationSpeed = 30000L // 30 seconds average per activity
    
    // Current metrics
    private var currentAnomalyScore = 0f
    
    data class SessionEvent(
        val startTime: Long,
        val endTime: Long,
        val duration: Long,
        val activitiesVisited: Set<String>,
        val featuresUsed: Map<String, Int>
    )
    
    data class NavigationEvent(
        val fromActivity: String,
        val toActivity: String,
        val timestamp: Long,
        val duration: Long // Time spent in fromActivity
    )
    
    override suspend fun initialize(): Boolean {
        return try {
            loadBaseline()
            sessionStartTime = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    override suspend fun startMonitoring() {
        isActive = true
        sessionStartTime = System.currentTimeMillis()
        currentActivity = "MainActivity" // Default starting activity
        activityStartTime = sessionStartTime
    }
    
    override suspend fun stopMonitoring() {
        if (isActive) {
            endCurrentSession()
        }
        isActive = false
        sessionEvents.clear()
        navigationEvents.clear()
        featureUsage.clear()
    }
    
    override suspend fun getAnomalyScore(): Float {
        return if (sessionEvents.isEmpty() && navigationEvents.size < 3) {
            0f // Not enough data
        } else {
            calculateAnomalyScore()
        }
    }
    
    override fun getAnomalyScoreFlow(): Flow<AnomalyResult> {
        return anomalyFlow.asSharedFlow()
    }
    
    /**
     * Call when user navigates to a new activity
     */
    fun onActivityChanged(newActivity: String) {
        if (!isActive) return
        
        val currentTime = System.currentTimeMillis()
        val duration = currentTime - activityStartTime
        
        if (currentActivity.isNotEmpty()) {
            val navigationEvent = NavigationEvent(
                fromActivity = currentActivity,
                toActivity = newActivity,
                timestamp = currentTime,
                duration = duration
            )
            addNavigationEvent(navigationEvent)
        }
        
        currentActivity = newActivity
        activityStartTime = currentTime
        
        // Emit real-time anomaly score
        if (navigationEvents.size >= 3) {
            val score = calculateAnomalyScore()
            val result = AnomalyResult(
                agentId = agentId,
                score = score,
                timestamp = currentTime,
                details = mapOf(
                    "current_activity" to newActivity,
                    "navigation_count" to navigationEvents.size,
                    "session_duration" to (currentTime - sessionStartTime),
                    "activities_visited" to getUniqueActivities().size
                )
            )
            anomalyFlow.tryEmit(result)
        }
    }
    
    /**
     * Call when user uses a specific feature
     */
    fun onFeatureUsed(featureName: String) {
        if (!isActive) return
        
        featureUsage[featureName] = featureUsage.getOrDefault(featureName, 0) + 1
        
        // Check for unusual feature usage patterns
        val totalFeatures = featureUsage.values.sum()
        val sessionDuration = System.currentTimeMillis() - sessionStartTime
        val featureRate = totalFeatures.toFloat() / (sessionDuration / 60000f) // per minute
        
        if (totalFeatures >= 5) {
            val score = calculateAnomalyScore()
            val result = AnomalyResult(
                agentId = agentId,
                score = score,
                timestamp = System.currentTimeMillis(),
                details = mapOf(
                    "feature_used" to featureName,
                    "feature_rate" to featureRate,
                    "total_features_used" to totalFeatures,
                    "feature_usage_map" to featureUsage.toMap()
                )
            )
            anomalyFlow.tryEmit(result)
        }
    }
    
    /**
     * Call when session ends (app goes to background, user logs out, etc.)
     */
    fun endCurrentSession() {
        if (!isActive || sessionStartTime == 0L) return
        
        val endTime = System.currentTimeMillis()
        val sessionEvent = SessionEvent(
            startTime = sessionStartTime,
            endTime = endTime,
            duration = endTime - sessionStartTime,
            activitiesVisited = getUniqueActivities(),
            featuresUsed = featureUsage.toMap()
        )
        
        addSessionEvent(sessionEvent)
        
        // Reset for next session
        sessionStartTime = 0L
        featureUsage.clear()
    }
    
    private fun addSessionEvent(event: SessionEvent) {
        sessionEvents.add(event)
        if (sessionEvents.size > maxEvents) {
            sessionEvents.removeAt(0)
        }
    }
    
    private fun addNavigationEvent(event: NavigationEvent) {
        navigationEvents.add(event)
        if (navigationEvents.size > maxEvents) {
            navigationEvents.removeAt(0)
        }
    }
    
    private fun getUniqueActivities(): Set<String> {
        val activities = mutableSetOf<String>()
        navigationEvents.forEach {
            activities.add(it.fromActivity)
            activities.add(it.toActivity)
        }
        if (currentActivity.isNotEmpty()) {
            activities.add(currentActivity)
        }
        return activities
    }
    
    private fun calculateAnomalyScore(): Float {
        val sessionDeviation = calculateSessionDeviation()
        val navigationDeviation = calculateNavigationDeviation()
        val featureUsageDeviation = calculateFeatureUsageDeviation()
        val timingDeviation = calculateTimingDeviation()
        val patternDeviation = calculatePatternDeviation()
        
        // Combine deviations with weights
        val score = (sessionDeviation * 0.3f +
                    navigationDeviation * 0.25f +
                    featureUsageDeviation * 0.2f +
                    timingDeviation * 0.15f +
                    patternDeviation * 0.1f).coerceIn(0f, 1f)
        
        currentAnomalyScore = score
        return score
    }
    
    private fun calculateSessionDeviation(): Float {
        if (sessionEvents.isEmpty()) {
            // Use current session if no completed sessions
            val currentDuration = if (sessionStartTime > 0) {
                System.currentTimeMillis() - sessionStartTime
            } else {
                return 0f
            }
            val deviation = abs(currentDuration - baselineSessionDuration).toFloat() / baselineSessionDuration
            return (deviation / 3f).coerceIn(0f, 1f)
        }
        
        val recentSessions = sessionEvents.takeLast(5)
        val avgDuration = recentSessions.map { it.duration }.average().toLong()
        val deviation = abs(avgDuration - baselineSessionDuration).toFloat() / baselineSessionDuration
        return (deviation / 3f).coerceIn(0f, 1f)
    }
    
    private fun calculateNavigationDeviation(): Float {
        if (navigationEvents.size < 3) return 0f
        
        val recentNavigations = navigationEvents.takeLast(10)
        
        // Check navigation speed
        val avgNavigationDuration = recentNavigations.map { it.duration }.average().toLong()
        val speedDeviation = abs(avgNavigationDuration - baselineNavigationSpeed).toFloat() / baselineNavigationSpeed
        
        // Check for unusual navigation patterns (e.g., rapid back-and-forth)
        val backAndForthCount = countBackAndForthNavigations(recentNavigations)
        val backAndForthRatio = backAndForthCount.toFloat() / recentNavigations.size
        
        return ((speedDeviation / 2f) + backAndForthRatio).coerceIn(0f, 1f)
    }
    
    private fun calculateFeatureUsageDeviation(): Float {
        if (featureUsage.isEmpty()) return 0f
        
        val sessionDuration = if (sessionStartTime > 0) {
            System.currentTimeMillis() - sessionStartTime
        } else {
            return 0f
        }
        
        val totalFeatures = featureUsage.values.sum()
        val currentFeatureRate = totalFeatures.toFloat() / (sessionDuration / 60000f) // per minute
        
        val deviation = abs(currentFeatureRate - baselineFeatureUsageRate) / baselineFeatureUsageRate
        return (deviation / 2f).coerceIn(0f, 1f)
    }
    
    private fun calculateTimingDeviation(): Float {
        if (navigationEvents.size < 5) return 0f
        
        val recentEvents = navigationEvents.takeLast(10)
        val intervals = mutableListOf<Long>()
        
        for (i in 1 until recentEvents.size) {
            intervals.add(recentEvents[i].timestamp - recentEvents[i-1].timestamp)
        }
        
        if (intervals.isEmpty()) return 0f
        
        // Check for unusually regular or irregular timing patterns
        val avgInterval = intervals.average()
        val variance = intervals.map { (it - avgInterval) * (it - avgInterval) }.average()
        val coefficient = if (avgInterval > 0) Math.sqrt(variance) / avgInterval else 0.0
        
        // High coefficient of variation indicates irregular behavior
        // Low coefficient might indicate automated behavior
        return when {
            coefficient > 1.0 -> 0.8f // Very irregular
            coefficient < 0.1 -> 0.6f // Too regular (possibly automated)
            else -> (coefficient / 2.0).toFloat().coerceIn(0f, 1f)
        }
    }
    
    private fun calculatePatternDeviation(): Float {
        if (navigationEvents.size < 10) return 0f
        
        val recentEvents = navigationEvents.takeLast(20)
        
        // Analyze navigation sequences
        val sequences = mutableMapOf<String, Int>()
        for (i in 1 until recentEvents.size) {
            val sequence = "${recentEvents[i-1].toActivity}->${recentEvents[i].toActivity}"
            sequences[sequence] = sequences.getOrDefault(sequence, 0) + 1
        }
        
        // Check for repetitive patterns
        val maxSequenceCount = sequences.values.maxOrNull() ?: 0
        val repetitionRatio = maxSequenceCount.toFloat() / recentEvents.size
        
        return (repetitionRatio * 2f).coerceIn(0f, 1f)
    }
    
    private fun countBackAndForthNavigations(events: List<NavigationEvent>): Int {
        var count = 0
        for (i in 1 until events.size) {
            val current = events[i]
            val previous = events[i-1]
            if (current.toActivity == previous.fromActivity && current.fromActivity == previous.toActivity) {
                count++
            }
        }
        return count
    }
    
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun getAppUsageStats(): List<UsageStats> {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val calendar = Calendar.getInstance()
        val endTime = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, -1) // Last 24 hours
        val startTime = calendar.timeInMillis
        
        return usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )
    }
    
    private fun loadBaseline() {
        val prefs = context.getSharedPreferences("usage_baseline", Context.MODE_PRIVATE)
        baselineSessionDuration = prefs.getLong("session_duration", 300000L)
        baselineActivitiesPerSession = prefs.getInt("activities_per_session", 5)
        baselineFeatureUsageRate = prefs.getFloat("feature_usage_rate", 0.1f)
        baselineNavigationSpeed = prefs.getLong("navigation_speed", 30000L)
    }
    
    /**
     * Update baseline metrics based on normal usage patterns
     */
    fun updateBaseline() {
        if (sessionEvents.size < 5) return
        
        val recentSessions = sessionEvents.takeLast(20)
        baselineSessionDuration = recentSessions.map { it.duration }.average().toLong()
        baselineActivitiesPerSession = recentSessions.map { it.activitiesVisited.size }.average().toInt()
        
        val totalFeatures = recentSessions.sumOf { it.featuresUsed.values.sum() }
        val totalDuration = recentSessions.sumOf { it.duration }
        baselineFeatureUsageRate = totalFeatures.toFloat() / (totalDuration / 60000f)
        
        if (navigationEvents.size >= 10) {
            val recentNavigations = navigationEvents.takeLast(50)
            baselineNavigationSpeed = recentNavigations.map { it.duration }.average().toLong()
        }
        
        // Save to preferences
        val prefs = context.getSharedPreferences("usage_baseline", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("session_duration", baselineSessionDuration)
            .putInt("activities_per_session", baselineActivitiesPerSession)
            .putFloat("feature_usage_rate", baselineFeatureUsageRate)
            .putLong("navigation_speed", baselineNavigationSpeed)
            .apply()
    }
    
    /**
     * Get current usage statistics for analysis
     */
    fun getUsageStats(): Map<String, Any> {
        val currentSessionDuration = if (sessionStartTime > 0) {
            System.currentTimeMillis() - sessionStartTime
        } else {
            0L
        }
        
        return mapOf(
            "current_session_duration" to currentSessionDuration,
            "activities_visited" to getUniqueActivities().size,
            "navigation_count" to navigationEvents.size,
            "features_used" to featureUsage.values.sum(),
            "session_count" to sessionEvents.size,
            "anomaly_score" to currentAnomalyScore,
            "current_activity" to currentActivity
        )
    }
}