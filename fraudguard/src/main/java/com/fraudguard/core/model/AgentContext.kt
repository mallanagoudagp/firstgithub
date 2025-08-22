package com.fraudguard.core.model

/**
 * Input context passed to all agents. Populate the fields relevant to the agents you use.
 */
data class AgentContext(
    val sessionId: String? = null,
    val userId: String? = null,
    val touchEvents: List<TouchEvent>? = null,
    val typingEvents: List<TypingEvent>? = null,
    val appUsageEvents: List<UsageEvent>? = null,
    val movementSamples: List<MovementSample>? = null,
    val request: RequestMeta? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

// Touch

data class TouchEvent(
    val gesture: String,
    val durationMs: Long,
    val pressure: Float,
    val velocity: Float,
    val timestampMs: Long
)

// Typing

data class TypingEvent(
    val keyCode: Int,
    val isKeyDown: Boolean,
    val timestampMs: Long
)

// Usage

data class UsageEvent(
    val activityName: String,
    val durationMs: Long,
    val inBackground: Boolean,
    val timestampMs: Long
)

// Movement

data class MovementSample(
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val speedMps: Float?,
    val latitude: Double?,
    val longitude: Double?,
    val timestampMs: Long
)

// Request metadata (e.g., for honeypot or API evaluation)

data class RequestMeta(
    val url: String,
    val method: String,
    val headers: Map<String, String> = emptyMap(),
    val bodyBytes: Int? = null
)