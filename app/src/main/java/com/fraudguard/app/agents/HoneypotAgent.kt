package com.fraudguard.app.agents

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.fraudguard.app.core.AnomalyResult
import com.fraudguard.app.core.FraudAgent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * HoneypotAgent creates fake endpoints and traps to detect automated attacks
 * Monitors network requests, fake form submissions, and hidden element interactions
 */
class HoneypotAgent(
    private val context: Context,
    override val weight: Float = 0.2f
) : FraudAgent {
    
    override val agentId: String = "HoneypotAgent"
    override var isActive: Boolean = false
        private set
    
    private val anomalyFlow = MutableSharedFlow<AnomalyResult>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Honeypot data
    private val trapInteractions = mutableListOf<TrapInteraction>()
    private val networkRequests = mutableListOf<NetworkRequest>()
    private val maxEvents = 100
    
    // Fake endpoints and traps
    private val fakeEndpoints = listOf(
        "/api/admin/users",
        "/wp-admin/",
        "/phpmyadmin/",
        "/api/v1/internal",
        "/admin/config",
        "/debug/info",
        "/api/secret"
    )
    
    private val hiddenFormFields = mapOf(
        "email_confirm" to "", // Should remain empty
        "website_url" to "", // Honeypot field
        "user_token" to generateRandomToken(),
        "timestamp_check" to System.currentTimeMillis().toString()
    )
    
    // Tracking data
    private val suspiciousIPs = ConcurrentHashMap<String, Int>()
    private val requestPatterns = ConcurrentHashMap<String, Int>()
    private var baselineRequestRate = 1.0f // requests per minute
    private var currentAnomalyScore = 0f
    
    data class TrapInteraction(
        val trapType: TrapType,
        val fieldName: String,
        val value: String,
        val timestamp: Long,
        val userAgent: String?,
        val ipAddress: String?
    )
    
    data class NetworkRequest(
        val endpoint: String,
        val method: String,
        val timestamp: Long,
        val userAgent: String?,
        val ipAddress: String?,
        val responseTime: Long,
        val statusCode: Int
    )
    
    enum class TrapType {
        HIDDEN_FIELD,
        FAKE_ENDPOINT,
        TIMING_TRAP,
        BEHAVIOR_TRAP
    }
    
    override suspend fun initialize(): Boolean {
        return try {
            loadBaseline()
            setupHoneypots()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    override suspend fun startMonitoring() {
        isActive = true
        startNetworkMonitoring()
    }
    
    override suspend fun stopMonitoring() {
        isActive = false
        scope.cancel()
        trapInteractions.clear()
        networkRequests.clear()
        suspiciousIPs.clear()
    }
    
    override suspend fun getAnomalyScore(): Float {
        return if (trapInteractions.isEmpty() && networkRequests.size < 5) {
            0f
        } else {
            calculateAnomalyScore()
        }
    }
    
    override fun getAnomalyScoreFlow(): Flow<AnomalyResult> {
        return anomalyFlow.asSharedFlow()
    }
    
    /**
     * Call when a hidden form field is filled
     */
    fun onHiddenFieldInteraction(fieldName: String, value: String, userAgent: String? = null) {
        if (!isActive) return
        
        val interaction = TrapInteraction(
            trapType = TrapType.HIDDEN_FIELD,
            fieldName = fieldName,
            value = value,
            timestamp = System.currentTimeMillis(),
            userAgent = userAgent,
            ipAddress = getCurrentIPAddress()
        )
        
        addTrapInteraction(interaction)
        
        // Hidden fields should never be filled by humans
        val result = AnomalyResult(
            agentId = agentId,
            score = 0.9f, // Very high score for honeypot field interaction
            timestamp = System.currentTimeMillis(),
            details = mapOf(
                "trap_type" to "HIDDEN_FIELD",
                "field_name" to fieldName,
                "field_value" to value,
                "user_agent" to (userAgent ?: "unknown")
            ),
            confidence = 0.95f
        )
        anomalyFlow.tryEmit(result)
    }
    
    /**
     * Call when a fake endpoint is accessed
     */
    fun onFakeEndpointAccess(endpoint: String, method: String, userAgent: String? = null) {
        if (!isActive) return
        
        val interaction = TrapInteraction(
            trapType = TrapType.FAKE_ENDPOINT,
            fieldName = endpoint,
            value = method,
            timestamp = System.currentTimeMillis(),
            userAgent = userAgent,
            ipAddress = getCurrentIPAddress()
        )
        
        addTrapInteraction(interaction)
        
        val suspiciousScore = when {
            endpoint.contains("admin") -> 0.8f
            endpoint.contains("debug") -> 0.7f
            endpoint.contains("internal") -> 0.9f
            else -> 0.6f
        }
        
        val result = AnomalyResult(
            agentId = agentId,
            score = suspiciousScore,
            timestamp = System.currentTimeMillis(),
            details = mapOf(
                "trap_type" to "FAKE_ENDPOINT",
                "endpoint" to endpoint,
                "method" to method,
                "user_agent" to (userAgent ?: "unknown")
            ),
            confidence = 0.85f
        )
        anomalyFlow.tryEmit(result)
    }
    
    /**
     * Call when timing-based trap is triggered (e.g., form submitted too quickly)
     */
    fun onTimingTrap(formName: String, submissionTime: Long, expectedMinTime: Long) {
        if (!isActive) return
        
        if (submissionTime < expectedMinTime) {
            val interaction = TrapInteraction(
                trapType = TrapType.TIMING_TRAP,
                fieldName = formName,
                value = submissionTime.toString(),
                timestamp = System.currentTimeMillis(),
                userAgent = null,
                ipAddress = getCurrentIPAddress()
            )
            
            addTrapInteraction(interaction)
            
            val speedRatio = expectedMinTime.toFloat() / submissionTime
            val score = (speedRatio / 10f).coerceIn(0.3f, 0.9f)
            
            val result = AnomalyResult(
                agentId = agentId,
                score = score,
                timestamp = System.currentTimeMillis(),
                details = mapOf(
                    "trap_type" to "TIMING_TRAP",
                    "form_name" to formName,
                    "submission_time" to submissionTime,
                    "expected_min_time" to expectedMinTime,
                    "speed_ratio" to speedRatio
                ),
                confidence = 0.7f
            )
            anomalyFlow.tryEmit(result)
        }
    }
    
    /**
     * Monitor network request patterns
     */
    private fun startNetworkMonitoring() {
        scope.launch {
            while (isActive) {
                try {
                    monitorRequestPatterns()
                    delay(30000) // Check every 30 seconds
                } catch (e: Exception) {
                    // Handle monitoring errors
                }
            }
        }
    }
    
    private suspend fun monitorRequestPatterns() {
        val currentTime = System.currentTimeMillis()
        val recentRequests = networkRequests.filter { 
            currentTime - it.timestamp < 60000 // Last minute
        }
        
        if (recentRequests.size > baselineRequestRate * 5) {
            // Unusually high request rate
            val result = AnomalyResult(
                agentId = agentId,
                score = 0.6f,
                timestamp = currentTime,
                details = mapOf(
                    "trap_type" to "HIGH_REQUEST_RATE",
                    "request_count" to recentRequests.size,
                    "baseline_rate" to baselineRequestRate,
                    "time_window" to "1_minute"
                ),
                confidence = 0.6f
            )
            anomalyFlow.tryEmit(result)
        }
        
        // Check for suspicious patterns
        val userAgents = recentRequests.groupBy { it.userAgent }
        userAgents.forEach { (userAgent, requests) ->
            if (requests.size > 20 && userAgent != null) {
                if (isSuspiciousUserAgent(userAgent)) {
                    val result = AnomalyResult(
                        agentId = agentId,
                        score = 0.7f,
                        timestamp = currentTime,
                        details = mapOf(
                            "trap_type" to "SUSPICIOUS_USER_AGENT",
                            "user_agent" to userAgent,
                            "request_count" to requests.size
                        ),
                        confidence = 0.8f
                    )
                    anomalyFlow.tryEmit(result)
                }
            }
        }
    }
    
    private fun isSuspiciousUserAgent(userAgent: String): Boolean {
        val suspiciousPatterns = listOf(
            "bot", "crawler", "spider", "scraper", "curl", "wget", 
            "python", "java", "go-http", "okhttp", "automated"
        )
        
        return suspiciousPatterns.any { pattern ->
            userAgent.lowercase().contains(pattern)
        }
    }
    
    private fun addTrapInteraction(interaction: TrapInteraction) {
        trapInteractions.add(interaction)
        if (trapInteractions.size > maxEvents) {
            trapInteractions.removeAt(0)
        }
        
        // Track suspicious IPs
        interaction.ipAddress?.let { ip ->
            suspiciousIPs[ip] = suspiciousIPs.getOrDefault(ip, 0) + 1
        }
    }
    
    private fun addNetworkRequest(request: NetworkRequest) {
        networkRequests.add(request)
        if (networkRequests.size > maxEvents) {
            networkRequests.removeAt(0)
        }
    }
    
    private fun calculateAnomalyScore(): Float {
        val trapScore = calculateTrapScore()
        val patternScore = calculatePatternScore()
        val frequencyScore = calculateFrequencyScore()
        val behaviorScore = calculateBehaviorScore()
        
        // Combine scores with weights
        val score = (trapScore * 0.4f +
                    patternScore * 0.25f +
                    frequencyScore * 0.2f +
                    behaviorScore * 0.15f).coerceIn(0f, 1f)
        
        currentAnomalyScore = score
        return score
    }
    
    private fun calculateTrapScore(): Float {
        if (trapInteractions.isEmpty()) return 0f
        
        val recentTraps = trapInteractions.filter { 
            System.currentTimeMillis() - it.timestamp < 300000 // Last 5 minutes
        }
        
        if (recentTraps.isEmpty()) return 0f
        
        // Weight different trap types
        var totalScore = 0f
        recentTraps.forEach { trap ->
            totalScore += when (trap.trapType) {
                TrapType.HIDDEN_FIELD -> 0.9f
                TrapType.FAKE_ENDPOINT -> 0.7f
                TrapType.TIMING_TRAP -> 0.6f
                TrapType.BEHAVIOR_TRAP -> 0.5f
            }
        }
        
        return (totalScore / recentTraps.size).coerceIn(0f, 1f)
    }
    
    private fun calculatePatternScore(): Float {
        val currentTime = System.currentTimeMillis()
        val recentRequests = networkRequests.filter { 
            currentTime - it.timestamp < 300000 // Last 5 minutes
        }
        
        if (recentRequests.size < 5) return 0f
        
        // Check for automated patterns
        val intervals = mutableListOf<Long>()
        for (i in 1 until recentRequests.size) {
            intervals.add(recentRequests[i].timestamp - recentRequests[i-1].timestamp)
        }
        
        if (intervals.isEmpty()) return 0f
        
        val avgInterval = intervals.average()
        val variance = intervals.map { (it - avgInterval) * (it - avgInterval) }.average()
        val coefficient = if (avgInterval > 0) Math.sqrt(variance) / avgInterval else 0.0
        
        // Very regular intervals suggest automation
        return if (coefficient < 0.1) 0.7f else (1.0f - coefficient.toFloat()).coerceIn(0f, 1f)
    }
    
    private fun calculateFrequencyScore(): Float {
        val currentTime = System.currentTimeMillis()
        val recentRequests = networkRequests.filter { 
            currentTime - it.timestamp < 60000 // Last minute
        }
        
        val currentRate = recentRequests.size.toFloat()
        val deviation = if (baselineRequestRate > 0) {
            (currentRate - baselineRequestRate) / baselineRequestRate
        } else {
            currentRate / 10f
        }
        
        return if (deviation > 2f) 0.8f else (deviation / 5f).coerceIn(0f, 1f)
    }
    
    private fun calculateBehaviorScore(): Float {
        // Check for repeated access to suspicious endpoints
        val suspiciousEndpoints = networkRequests.filter { request ->
            fakeEndpoints.any { endpoint -> request.endpoint.contains(endpoint) }
        }
        
        return if (suspiciousEndpoints.isNotEmpty()) {
            (suspiciousEndpoints.size.toFloat() / networkRequests.size * 2f).coerceIn(0f, 1f)
        } else {
            0f
        }
    }
    
    private fun setupHoneypots() {
        // This would typically involve setting up fake endpoints in your web server
        // For this example, we're just initializing the trap data structures
    }
    
    private fun getCurrentIPAddress(): String? {
        // In a real implementation, you would get the actual client IP
        // This is a placeholder
        return "127.0.0.1"
    }
    
    private fun generateRandomToken(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..32)
            .map { chars[Random.nextInt(chars.length)] }
            .joinToString("")
    }
    
    private fun loadBaseline() {
        val prefs = context.getSharedPreferences("honeypot_baseline", Context.MODE_PRIVATE)
        baselineRequestRate = prefs.getFloat("request_rate", 1.0f)
    }
    
    /**
     * Update baseline metrics based on normal request patterns
     */
    fun updateBaseline() {
        if (networkRequests.size < 20) return
        
        val currentTime = System.currentTimeMillis()
        val recentRequests = networkRequests.filter { 
            currentTime - it.timestamp < 3600000 // Last hour
        }
        
        if (recentRequests.isNotEmpty()) {
            baselineRequestRate = recentRequests.size.toFloat() / 60f // per minute
            
            val prefs = context.getSharedPreferences("honeypot_baseline", Context.MODE_PRIVATE)
            prefs.edit()
                .putFloat("request_rate", baselineRequestRate)
                .apply()
        }
    }
    
    /**
     * Get current honeypot statistics
     */
    fun getHoneypotStats(): Map<String, Any> {
        val currentTime = System.currentTimeMillis()
        val recentTraps = trapInteractions.filter { 
            currentTime - it.timestamp < 3600000 // Last hour
        }
        
        return mapOf(
            "total_trap_interactions" to trapInteractions.size,
            "recent_trap_interactions" to recentTraps.size,
            "suspicious_ips" to suspiciousIPs.size,
            "network_requests" to networkRequests.size,
            "baseline_request_rate" to baselineRequestRate,
            "anomaly_score" to currentAnomalyScore,
            "trap_types" to recentTraps.groupBy { it.trapType }.mapValues { it.value.size }
        )
    }
    
    /**
     * Check if current network is secure
     */
    private fun isNetworkSecure(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            when {
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    // Check if WiFi is encrypted (this is a simplified check)
                    true // In reality, you'd check WiFi security
                }
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
                else -> false
            }
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo?.isConnected == true
        }
    }
    
    /**
     * Generate honeypot form fields for web views
     */
    fun getHoneypotFormFields(): Map<String, String> {
        return hiddenFormFields
    }
    
    /**
     * Validate form submission against honeypot rules
     */
    fun validateFormSubmission(formData: Map<String, String>, submissionTime: Long, formLoadTime: Long): Float {
        var suspiciousScore = 0f
        
        // Check hidden fields
        hiddenFormFields.forEach { (fieldName, expectedValue) ->
            val submittedValue = formData[fieldName] ?: ""
            if (submittedValue != expectedValue) {
                suspiciousScore += 0.3f
                onHiddenFieldInteraction(fieldName, submittedValue)
            }
        }
        
        // Check timing
        val fillTime = submissionTime - formLoadTime
        if (fillTime < 2000) { // Less than 2 seconds
            suspiciousScore += 0.4f
            onTimingTrap("form_submission", fillTime, 2000L)
        }
        
        return suspiciousScore.coerceIn(0f, 1f)
    }
}