package com.fraudguard.app.agents

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.fraudguard.app.core.AnomalyResult
import com.fraudguard.app.core.FraudAgent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * TouchAgent monitors touch gestures and interactions to detect behavioral anomalies
 * Analyzes pressure, velocity, timing, and gesture patterns
 */
class TouchAgent(
    private val context: Context,
    override val weight: Float = 0.25f
) : FraudAgent {
    
    override val agentId: String = "TouchAgent"
    override var isActive: Boolean = false
        private set
    
    private val anomalyFlow = MutableSharedFlow<AnomalyResult>()
    
    // Touch pattern data
    private val touchEvents = mutableListOf<TouchEvent>()
    private val maxEvents = 100
    
    // Baseline metrics (learned from normal behavior)
    private var baselinePressure = 0.5f
    private var baselineVelocity = 100f
    private var baselineDwellTime = 150L
    private var baselineDistance = 50f
    
    // Current session metrics
    private var currentAnomalyScore = 0f
    
    data class TouchEvent(
        val x: Float,
        val y: Float,
        val pressure: Float,
        val timestamp: Long,
        val action: Int,
        val velocity: Float = 0f
    )
    
    override suspend fun initialize(): Boolean {
        return try {
            // Load baseline metrics from preferences or database
            loadBaseline()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    override suspend fun startMonitoring() {
        isActive = true
        // Touch monitoring is handled through onTouchEvent calls
    }
    
    override suspend fun stopMonitoring() {
        isActive = false
        touchEvents.clear()
    }
    
    override suspend fun getAnomalyScore(): Float {
        return if (touchEvents.size < 5) {
            0f // Not enough data
        } else {
            calculateAnomalyScore()
        }
    }
    
    override fun getAnomalyScoreFlow(): Flow<AnomalyResult> {
        return anomalyFlow.asSharedFlow()
    }
    
    /**
     * Call this method from touch event handlers in your activities/views
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isActive) return false
        
        val touchEvent = TouchEvent(
            x = event.x,
            y = event.y,
            pressure = event.pressure,
            timestamp = System.currentTimeMillis(),
            action = event.action,
            velocity = calculateVelocity(event)
        )
        
        addTouchEvent(touchEvent)
        
        // Emit real-time anomaly score
        if (touchEvents.size >= 5) {
            val score = calculateAnomalyScore()
            val result = AnomalyResult(
                agentId = agentId,
                score = score,
                timestamp = System.currentTimeMillis(),
                details = mapOf(
                    "pressure_deviation" to abs(touchEvent.pressure - baselinePressure),
                    "velocity_deviation" to abs(touchEvent.velocity - baselineVelocity),
                    "event_count" to touchEvents.size
                )
            )
            anomalyFlow.tryEmit(result)
        }
        
        return false
    }
    
    private fun addTouchEvent(event: TouchEvent) {
        touchEvents.add(event)
        if (touchEvents.size > maxEvents) {
            touchEvents.removeAt(0)
        }
    }
    
    private fun calculateVelocity(event: MotionEvent): Float {
        if (touchEvents.isEmpty()) return 0f
        
        val lastEvent = touchEvents.last()
        val deltaX = event.x - lastEvent.x
        val deltaY = event.y - lastEvent.y
        val deltaTime = event.eventTime - lastEvent.timestamp
        
        return if (deltaTime > 0) {
            sqrt(deltaX * deltaX + deltaY * deltaY) / deltaTime * 1000
        } else {
            0f
        }
    }
    
    private fun calculateAnomalyScore(): Float {
        if (touchEvents.size < 5) return 0f
        
        val recentEvents = touchEvents.takeLast(10)
        
        // Calculate deviations from baseline
        val pressureDeviation = calculatePressureDeviation(recentEvents)
        val velocityDeviation = calculateVelocityDeviation(recentEvents)
        val timingDeviation = calculateTimingDeviation(recentEvents)
        val patternDeviation = calculatePatternDeviation(recentEvents)
        
        // Combine deviations with weights
        val score = (pressureDeviation * 0.2f +
                    velocityDeviation * 0.3f +
                    timingDeviation * 0.25f +
                    patternDeviation * 0.25f).coerceIn(0f, 1f)
        
        currentAnomalyScore = score
        return score
    }
    
    private fun calculatePressureDeviation(events: List<TouchEvent>): Float {
        val avgPressure = events.map { it.pressure }.average().toFloat()
        val deviation = abs(avgPressure - baselinePressure) / baselinePressure
        return deviation.coerceIn(0f, 1f)
    }
    
    private fun calculateVelocityDeviation(events: List<TouchEvent>): Float {
        val avgVelocity = events.map { it.velocity }.average().toFloat()
        val deviation = abs(avgVelocity - baselineVelocity) / baselineVelocity
        return (deviation / 2f).coerceIn(0f, 1f) // Normalize
    }
    
    private fun calculateTimingDeviation(events: List<TouchEvent>): Float {
        if (events.size < 2) return 0f
        
        val intervals = mutableListOf<Long>()
        for (i in 1 until events.size) {
            intervals.add(events[i].timestamp - events[i-1].timestamp)
        }
        
        val avgInterval = intervals.average().toLong()
        val deviation = abs(avgInterval - baselineDwellTime).toFloat() / baselineDwellTime
        return (deviation / 3f).coerceIn(0f, 1f) // Normalize
    }
    
    private fun calculatePatternDeviation(events: List<TouchEvent>): Float {
        if (events.size < 3) return 0f
        
        // Analyze gesture smoothness and consistency
        var totalDirectionChange = 0f
        for (i in 2 until events.size) {
            val angle1 = calculateAngle(events[i-2], events[i-1])
            val angle2 = calculateAngle(events[i-1], events[i])
            totalDirectionChange += abs(angle2 - angle1)
        }
        
        val avgDirectionChange = totalDirectionChange / (events.size - 2)
        return (avgDirectionChange / 180f).coerceIn(0f, 1f) // Normalize to 0-1
    }
    
    private fun calculateAngle(event1: TouchEvent, event2: TouchEvent): Float {
        val deltaX = event2.x - event1.x
        val deltaY = event2.y - event1.y
        return Math.toDegrees(kotlin.math.atan2(deltaY.toDouble(), deltaX.toDouble())).toFloat()
    }
    
    private fun loadBaseline() {
        // In a real implementation, load from SharedPreferences or database
        // For now, using default values
        val prefs = context.getSharedPreferences("touch_baseline", Context.MODE_PRIVATE)
        baselinePressure = prefs.getFloat("pressure", 0.5f)
        baselineVelocity = prefs.getFloat("velocity", 100f)
        baselineDwellTime = prefs.getLong("dwell_time", 150L)
        baselineDistance = prefs.getFloat("distance", 50f)
    }
    
    /**
     * Update baseline metrics based on normal user behavior
     */
    fun updateBaseline() {
        if (touchEvents.size < 20) return
        
        val recentEvents = touchEvents.takeLast(50)
        baselinePressure = recentEvents.map { it.pressure }.average().toFloat()
        baselineVelocity = recentEvents.map { it.velocity }.average().toFloat()
        
        // Save to preferences
        val prefs = context.getSharedPreferences("touch_baseline", Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat("pressure", baselinePressure)
            .putFloat("velocity", baselineVelocity)
            .putLong("dwell_time", baselineDwellTime)
            .putFloat("distance", baselineDistance)
            .apply()
    }
}