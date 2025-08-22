package com.fraudguard.app.agents

import android.content.Context
import android.view.KeyEvent
import com.fraudguard.app.core.AnomalyResult
import com.fraudguard.app.core.FraudAgent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * TypingAgent monitors keystroke dynamics and typing patterns to detect behavioral anomalies
 * Analyzes dwell time, flight time, typing rhythm, and pressure patterns
 */
class TypingAgent(
    private val context: Context,
    override val weight: Float = 0.2f
) : FraudAgent {
    
    override val agentId: String = "TypingAgent"
    override var isActive: Boolean = false
        private set
    
    private val anomalyFlow = MutableSharedFlow<AnomalyResult>()
    
    // Keystroke data
    private val keystrokes = mutableListOf<KeystrokeEvent>()
    private val maxKeystrokes = 200
    
    // Baseline metrics for typing behavior
    private var baselineDwellTime = 100L // Time key is held down
    private var baselineFlightTime = 150L // Time between key releases
    private var baselineTypingSpeed = 200L // Average time between keystrokes
    private var baselineRhythm = 50L // Standard deviation of intervals
    
    // Current session metrics
    private var currentAnomalyScore = 0f
    private val keyPressMap = mutableMapOf<Int, Long>() // Track key press times
    
    data class KeystrokeEvent(
        val keyCode: Int,
        val pressTime: Long,
        val releaseTime: Long,
        val dwellTime: Long, // Time key was held
        val flightTime: Long, // Time since last key release
        val character: Char?
    )
    
    override suspend fun initialize(): Boolean {
        return try {
            loadBaseline()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    override suspend fun startMonitoring() {
        isActive = true
        keystrokes.clear()
        keyPressMap.clear()
    }
    
    override suspend fun stopMonitoring() {
        isActive = false
        keystrokes.clear()
        keyPressMap.clear()
    }
    
    override suspend fun getAnomalyScore(): Float {
        return if (keystrokes.size < 5) {
            0f // Not enough data
        } else {
            calculateAnomalyScore()
        }
    }
    
    override fun getAnomalyScoreFlow(): Flow<AnomalyResult> {
        return anomalyFlow.asSharedFlow()
    }
    
    /**
     * Call this method from key event handlers in your activities/views
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isActive) return false
        
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                keyPressMap[event.keyCode] = event.eventTime
            }
            KeyEvent.ACTION_UP -> {
                val pressTime = keyPressMap[event.keyCode]
                if (pressTime != null) {
                    val keystrokeEvent = createKeystrokeEvent(event, pressTime)
                    addKeystrokeEvent(keystrokeEvent)
                    keyPressMap.remove(event.keyCode)
                    
                    // Emit real-time anomaly score
                    if (keystrokes.size >= 5) {
                        val score = calculateAnomalyScore()
                        val result = AnomalyResult(
                            agentId = agentId,
                            score = score,
                            timestamp = System.currentTimeMillis(),
                            details = mapOf(
                                "dwell_time" to keystrokeEvent.dwellTime,
                                "flight_time" to keystrokeEvent.flightTime,
                                "keystroke_count" to keystrokes.size,
                                "typing_speed_deviation" to calculateTypingSpeedDeviation()
                            )
                        )
                        anomalyFlow.tryEmit(result)
                    }
                }
            }
        }
        
        return false
    }
    
    private fun createKeystrokeEvent(event: KeyEvent, pressTime: Long): KeystrokeEvent {
        val releaseTime = event.eventTime
        val dwellTime = releaseTime - pressTime
        
        // Calculate flight time (time since last key release)
        val flightTime = if (keystrokes.isNotEmpty()) {
            pressTime - keystrokes.last().releaseTime
        } else {
            0L
        }
        
        return KeystrokeEvent(
            keyCode = event.keyCode,
            pressTime = pressTime,
            releaseTime = releaseTime,
            dwellTime = dwellTime,
            flightTime = flightTime,
            character = if (event.isPrintingKey) event.displayLabel else null
        )
    }
    
    private fun addKeystrokeEvent(event: KeystrokeEvent) {
        keystrokes.add(event)
        if (keystrokes.size > maxKeystrokes) {
            keystrokes.removeAt(0)
        }
    }
    
    private fun calculateAnomalyScore(): Float {
        if (keystrokes.size < 5) return 0f
        
        val recentKeystrokes = keystrokes.takeLast(20)
        
        // Calculate various typing behavior deviations
        val dwellTimeDeviation = calculateDwellTimeDeviation(recentKeystrokes)
        val flightTimeDeviation = calculateFlightTimeDeviation(recentKeystrokes)
        val rhythmDeviation = calculateRhythmDeviation(recentKeystrokes)
        val speedDeviation = calculateSpeedDeviation(recentKeystrokes)
        val patternDeviation = calculatePatternDeviation(recentKeystrokes)
        
        // Combine deviations with weights
        val score = (dwellTimeDeviation * 0.25f +
                    flightTimeDeviation * 0.25f +
                    rhythmDeviation * 0.2f +
                    speedDeviation * 0.15f +
                    patternDeviation * 0.15f).coerceIn(0f, 1f)
        
        currentAnomalyScore = score
        return score
    }
    
    private fun calculateDwellTimeDeviation(keystrokes: List<KeystrokeEvent>): Float {
        val avgDwellTime = keystrokes.map { it.dwellTime }.average().toLong()
        val deviation = abs(avgDwellTime - baselineDwellTime).toFloat() / baselineDwellTime
        return (deviation / 2f).coerceIn(0f, 1f)
    }
    
    private fun calculateFlightTimeDeviation(keystrokes: List<KeystrokeEvent>): Float {
        val validFlightTimes = keystrokes.filter { it.flightTime > 0 }
        if (validFlightTimes.isEmpty()) return 0f
        
        val avgFlightTime = validFlightTimes.map { it.flightTime }.average().toLong()
        val deviation = abs(avgFlightTime - baselineFlightTime).toFloat() / baselineFlightTime
        return (deviation / 2f).coerceIn(0f, 1f)
    }
    
    private fun calculateRhythmDeviation(keystrokes: List<KeystrokeEvent>): Float {
        if (keystrokes.size < 3) return 0f
        
        val intervals = mutableListOf<Long>()
        for (i in 1 until keystrokes.size) {
            intervals.add(keystrokes[i].pressTime - keystrokes[i-1].pressTime)
        }
        
        val avgInterval = intervals.average()
        val variance = intervals.map { (it - avgInterval) * (it - avgInterval) }.average()
        val stdDev = sqrt(variance).toLong()
        
        val deviation = abs(stdDev - baselineRhythm).toFloat() / baselineRhythm
        return (deviation / 3f).coerceIn(0f, 1f)
    }
    
    private fun calculateSpeedDeviation(keystrokes: List<KeystrokeEvent>): Float {
        if (keystrokes.size < 2) return 0f
        
        val totalTime = keystrokes.last().releaseTime - keystrokes.first().pressTime
        val avgKeystrokeTime = totalTime / keystrokes.size
        
        val deviation = abs(avgKeystrokeTime - baselineTypingSpeed).toFloat() / baselineTypingSpeed
        return (deviation / 2f).coerceIn(0f, 1f)
    }
    
    private fun calculatePatternDeviation(keystrokes: List<KeystrokeEvent>): Float {
        if (keystrokes.size < 10) return 0f
        
        // Analyze common key combinations and sequences
        val bigrams = mutableMapOf<Pair<Int, Int>, Int>()
        for (i in 1 until keystrokes.size) {
            val bigram = Pair(keystrokes[i-1].keyCode, keystrokes[i].keyCode)
            bigrams[bigram] = bigrams.getOrDefault(bigram, 0) + 1
        }
        
        // Check for unusual patterns (e.g., too many repeated keys, unusual sequences)
        val repeatedKeys = keystrokes.groupBy { it.keyCode }.values.maxOfOrNull { it.size } ?: 0
        val maxRepeatedRatio = repeatedKeys.toFloat() / keystrokes.size
        
        return (maxRepeatedRatio * 2f).coerceIn(0f, 1f)
    }
    
    private fun calculateTypingSpeedDeviation(): Float {
        if (keystrokes.size < 2) return 0f
        
        val recentKeystrokes = keystrokes.takeLast(10)
        val totalTime = recentKeystrokes.last().releaseTime - recentKeystrokes.first().pressTime
        val currentSpeed = totalTime / recentKeystrokes.size
        
        return abs(currentSpeed - baselineTypingSpeed).toFloat() / baselineTypingSpeed
    }
    
    private fun loadBaseline() {
        val prefs = context.getSharedPreferences("typing_baseline", Context.MODE_PRIVATE)
        baselineDwellTime = prefs.getLong("dwell_time", 100L)
        baselineFlightTime = prefs.getLong("flight_time", 150L)
        baselineTypingSpeed = prefs.getLong("typing_speed", 200L)
        baselineRhythm = prefs.getLong("rhythm", 50L)
    }
    
    /**
     * Update baseline metrics based on normal typing behavior
     */
    fun updateBaseline() {
        if (keystrokes.size < 50) return
        
        val recentKeystrokes = keystrokes.takeLast(100)
        
        baselineDwellTime = recentKeystrokes.map { it.dwellTime }.average().toLong()
        val validFlightTimes = recentKeystrokes.filter { it.flightTime > 0 }
        if (validFlightTimes.isNotEmpty()) {
            baselineFlightTime = validFlightTimes.map { it.flightTime }.average().toLong()
        }
        
        // Calculate typing speed
        val totalTime = recentKeystrokes.last().releaseTime - recentKeystrokes.first().pressTime
        baselineTypingSpeed = totalTime / recentKeystrokes.size
        
        // Calculate rhythm (standard deviation of intervals)
        val intervals = mutableListOf<Long>()
        for (i in 1 until recentKeystrokes.size) {
            intervals.add(recentKeystrokes[i].pressTime - recentKeystrokes[i-1].pressTime)
        }
        val avgInterval = intervals.average()
        val variance = intervals.map { (it - avgInterval) * (it - avgInterval) }.average()
        baselineRhythm = sqrt(variance).toLong()
        
        // Save to preferences
        val prefs = context.getSharedPreferences("typing_baseline", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("dwell_time", baselineDwellTime)
            .putLong("flight_time", baselineFlightTime)
            .putLong("typing_speed", baselineTypingSpeed)
            .putLong("rhythm", baselineRhythm)
            .apply()
    }
    
    /**
     * Get current typing statistics for analysis
     */
    fun getTypingStats(): Map<String, Any> {
        return mapOf(
            "keystroke_count" to keystrokes.size,
            "current_dwell_time" to if (keystrokes.isNotEmpty()) keystrokes.takeLast(10).map { it.dwellTime }.average() else 0.0,
            "current_flight_time" to if (keystrokes.isNotEmpty()) keystrokes.takeLast(10).filter { it.flightTime > 0 }.map { it.flightTime }.average() else 0.0,
            "baseline_dwell_time" to baselineDwellTime,
            "baseline_flight_time" to baselineFlightTime,
            "anomaly_score" to currentAnomalyScore
        )
    }
}