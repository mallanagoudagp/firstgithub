package com.fraudguard.app.agents

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.app.ActivityCompat
import com.fraudguard.app.core.AnomalyResult
import com.fraudguard.app.core.FraudAgent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * MovementAgent monitors device movement patterns using accelerometer and GPS data
 * Analyzes movement velocity, patterns, stability, and location changes to detect anomalies
 */
class MovementAgent(
    private val context: Context,
    override val weight: Float = 0.2f
) : FraudAgent, SensorEventListener, LocationListener {
    
    override val agentId: String = "MovementAgent"
    override var isActive: Boolean = false
        private set
    
    private val anomalyFlow = MutableSharedFlow<AnomalyResult>()
    
    // Sensor managers
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    
    // Sensors
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    
    // Movement data
    private val accelerometerData = mutableListOf<AccelerometerEvent>()
    private val locationData = mutableListOf<LocationEvent>()
    private val maxEvents = 200
    
    // Baseline metrics
    private var baselineAcceleration = 9.8f // Standard gravity
    private var baselineMovementVariance = 2.0f
    private var baselineLocationAccuracy = 10f // meters
    private var baselineVelocity = 1.0f // m/s walking speed
    
    // Current metrics
    private var currentAnomalyScore = 0f
    private var lastAcceleration = FloatArray(3)
    private var lastLocation: Location? = null
    
    data class AccelerometerEvent(
        val x: Float,
        val y: Float,
        val z: Float,
        val magnitude: Float,
        val timestamp: Long
    )
    
    data class LocationEvent(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val speed: Float,
        val timestamp: Long,
        val provider: String
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
        if (!isActive) {
            isActive = true
            
            // Register sensor listeners
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            gyroscope?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            
            // Request location updates if permissions are granted
            if (hasLocationPermission()) {
                try {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        5000, // 5 seconds
                        5f, // 5 meters
                        this
                    )
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        5000,
                        5f,
                        this
                    )
                } catch (e: SecurityException) {
                    // Handle permission error
                }
            }
        }
    }
    
    override suspend fun stopMonitoring() {
        if (isActive) {
            isActive = false
            sensorManager.unregisterListener(this)
            try {
                locationManager.removeUpdates(this)
            } catch (e: SecurityException) {
                // Handle permission error
            }
            accelerometerData.clear()
            locationData.clear()
        }
    }
    
    override suspend fun getAnomalyScore(): Float {
        return if (accelerometerData.size < 10 && locationData.size < 3) {
            0f // Not enough data
        } else {
            calculateAnomalyScore()
        }
    }
    
    override fun getAnomalyScoreFlow(): Flow<AnomalyResult> {
        return anomalyFlow.asSharedFlow()
    }
    
    // SensorEventListener implementation
    override fun onSensorChanged(event: SensorEvent?) {
        if (!isActive || event == null) return
        
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val magnitude = sqrt(event.values[0] * event.values[0] + 
                                   event.values[1] * event.values[1] + 
                                   event.values[2] * event.values[2])
                
                val accelerometerEvent = AccelerometerEvent(
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                    magnitude = magnitude,
                    timestamp = System.currentTimeMillis()
                )
                
                addAccelerometerEvent(accelerometerEvent)
                lastAcceleration = event.values.clone()
                
                // Emit real-time anomaly score
                if (accelerometerData.size >= 10) {
                    val score = calculateAnomalyScore()
                    val result = AnomalyResult(
                        agentId = agentId,
                        score = score,
                        timestamp = System.currentTimeMillis(),
                        details = mapOf(
                            "acceleration_magnitude" to magnitude,
                            "acceleration_deviation" to abs(magnitude - baselineAcceleration),
                            "movement_variance" to calculateMovementVariance(),
                            "data_points" to accelerometerData.size
                        )
                    )
                    anomalyFlow.tryEmit(result)
                }
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handle sensor accuracy changes if needed
    }
    
    // LocationListener implementation
    override fun onLocationChanged(location: Location) {
        if (!isActive) return
        
        val locationEvent = LocationEvent(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            speed = location.speed,
            timestamp = System.currentTimeMillis(),
            provider = location.provider ?: "unknown"
        )
        
        addLocationEvent(locationEvent)
        
        // Calculate movement patterns
        lastLocation?.let { prevLocation ->
            val distance = location.distanceTo(prevLocation)
            val timeDelta = location.time - prevLocation.time
            val velocity = if (timeDelta > 0) distance / (timeDelta / 1000f) else 0f
            
            // Emit location-based anomaly score
            if (locationData.size >= 3) {
                val score = calculateAnomalyScore()
                val result = AnomalyResult(
                    agentId = agentId,
                    score = score,
                    timestamp = System.currentTimeMillis(),
                    details = mapOf(
                        "location_accuracy" to location.accuracy,
                        "calculated_velocity" to velocity,
                        "distance_moved" to distance,
                        "location_provider" to location.provider,
                        "location_count" to locationData.size
                    )
                )
                anomalyFlow.tryEmit(result)
            }
        }
        
        lastLocation = location
    }
    
    @Deprecated("Deprecated in API level 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
        // Handle provider status changes if needed
    }
    
    override fun onProviderEnabled(provider: String) {
        // Handle provider enabled
    }
    
    override fun onProviderDisabled(provider: String) {
        // Handle provider disabled
    }
    
    private fun addAccelerometerEvent(event: AccelerometerEvent) {
        accelerometerData.add(event)
        if (accelerometerData.size > maxEvents) {
            accelerometerData.removeAt(0)
        }
    }
    
    private fun addLocationEvent(event: LocationEvent) {
        locationData.add(event)
        if (locationData.size > maxEvents) {
            locationData.removeAt(0)
        }
    }
    
    private fun calculateAnomalyScore(): Float {
        val accelerationDeviation = calculateAccelerationDeviation()
        val movementPatternDeviation = calculateMovementPatternDeviation()
        val velocityDeviation = calculateVelocityDeviation()
        val stabilityDeviation = calculateStabilityDeviation()
        val locationAccuracyDeviation = calculateLocationAccuracyDeviation()
        
        // Combine deviations with weights
        val score = (accelerationDeviation * 0.25f +
                    movementPatternDeviation * 0.2f +
                    velocityDeviation * 0.2f +
                    stabilityDeviation * 0.2f +
                    locationAccuracyDeviation * 0.15f).coerceIn(0f, 1f)
        
        currentAnomalyScore = score
        return score
    }
    
    private fun calculateAccelerationDeviation(): Float {
        if (accelerometerData.size < 10) return 0f
        
        val recentData = accelerometerData.takeLast(20)
        val avgMagnitude = recentData.map { it.magnitude }.average().toFloat()
        val deviation = abs(avgMagnitude - baselineAcceleration) / baselineAcceleration
        return (deviation / 2f).coerceIn(0f, 1f)
    }
    
    private fun calculateMovementPatternDeviation(): Float {
        if (accelerometerData.size < 20) return 0f
        
        val recentData = accelerometerData.takeLast(30)
        val movementVariance = calculateMovementVariance()
        val deviation = abs(movementVariance - baselineMovementVariance) / baselineMovementVariance
        return (deviation / 3f).coerceIn(0f, 1f)
    }
    
    private fun calculateMovementVariance(): Float {
        if (accelerometerData.size < 10) return 0f
        
        val recentData = accelerometerData.takeLast(20)
        val magnitudes = recentData.map { it.magnitude }
        val mean = magnitudes.average()
        val variance = magnitudes.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance).toFloat()
    }
    
    private fun calculateVelocityDeviation(): Float {
        if (locationData.size < 3) return 0f
        
        val recentLocations = locationData.takeLast(5)
        var totalVelocity = 0f
        var validMeasurements = 0
        
        for (i in 1 until recentLocations.size) {
            val current = recentLocations[i]
            val previous = recentLocations[i-1]
            
            val distance = calculateDistance(
                previous.latitude, previous.longitude,
                current.latitude, current.longitude
            )
            val timeDelta = (current.timestamp - previous.timestamp) / 1000f
            
            if (timeDelta > 0) {
                totalVelocity += distance / timeDelta
                validMeasurements++
            }
        }
        
        if (validMeasurements == 0) return 0f
        
        val avgVelocity = totalVelocity / validMeasurements
        val deviation = abs(avgVelocity - baselineVelocity) / baselineVelocity
        return (deviation / 5f).coerceIn(0f, 1f) // Normalize for reasonable velocity ranges
    }
    
    private fun calculateStabilityDeviation(): Float {
        if (accelerometerData.size < 15) return 0f
        
        val recentData = accelerometerData.takeLast(30)
        
        // Calculate jerk (rate of change of acceleration)
        val jerks = mutableListOf<Float>()
        for (i in 1 until recentData.size) {
            val current = recentData[i]
            val previous = recentData[i-1]
            val timeDelta = (current.timestamp - previous.timestamp) / 1000f
            
            if (timeDelta > 0) {
                val jerk = abs(current.magnitude - previous.magnitude) / timeDelta
                jerks.add(jerk)
            }
        }
        
        if (jerks.isEmpty()) return 0f
        
        val avgJerk = jerks.average().toFloat()
        // Higher jerk indicates less stable movement
        return (avgJerk / 50f).coerceIn(0f, 1f) // Normalize based on expected jerk values
    }
    
    private fun calculateLocationAccuracyDeviation(): Float {
        if (locationData.size < 3) return 0f
        
        val recentLocations = locationData.takeLast(5)
        val avgAccuracy = recentLocations.map { it.accuracy }.average().toFloat()
        
        // Poor accuracy indicates potential spoofing or interference
        val deviation = abs(avgAccuracy - baselineLocationAccuracy) / baselineLocationAccuracy
        return (deviation / 3f).coerceIn(0f, 1f)
    }
    
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        // Haversine formula for distance calculation
        val earthRadius = 6371000 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return (earthRadius * c).toFloat()
    }
    
    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun loadBaseline() {
        val prefs = context.getSharedPreferences("movement_baseline", Context.MODE_PRIVATE)
        baselineAcceleration = prefs.getFloat("acceleration", 9.8f)
        baselineMovementVariance = prefs.getFloat("movement_variance", 2.0f)
        baselineLocationAccuracy = prefs.getFloat("location_accuracy", 10f)
        baselineVelocity = prefs.getFloat("velocity", 1.0f)
    }
    
    /**
     * Update baseline metrics based on normal movement patterns
     */
    fun updateBaseline() {
        if (accelerometerData.size < 50) return
        
        val recentAccelData = accelerometerData.takeLast(100)
        baselineAcceleration = recentAccelData.map { it.magnitude }.average().toFloat()
        baselineMovementVariance = calculateMovementVariance()
        
        if (locationData.size >= 10) {
            val recentLocationData = locationData.takeLast(20)
            baselineLocationAccuracy = recentLocationData.map { it.accuracy }.average().toFloat()
            
            // Calculate baseline velocity
            var totalVelocity = 0f
            var validMeasurements = 0
            
            for (i in 1 until recentLocationData.size) {
                val current = recentLocationData[i]
                val previous = recentLocationData[i-1]
                
                val distance = calculateDistance(
                    previous.latitude, previous.longitude,
                    current.latitude, current.longitude
                )
                val timeDelta = (current.timestamp - previous.timestamp) / 1000f
                
                if (timeDelta > 0) {
                    totalVelocity += distance / timeDelta
                    validMeasurements++
                }
            }
            
            if (validMeasurements > 0) {
                baselineVelocity = totalVelocity / validMeasurements
            }
        }
        
        // Save to preferences
        val prefs = context.getSharedPreferences("movement_baseline", Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat("acceleration", baselineAcceleration)
            .putFloat("movement_variance", baselineMovementVariance)
            .putFloat("location_accuracy", baselineLocationAccuracy)
            .putFloat("velocity", baselineVelocity)
            .apply()
    }
    
    /**
     * Get current movement statistics for analysis
     */
    fun getMovementStats(): Map<String, Any> {
        val currentAcceleration = if (accelerometerData.isNotEmpty()) {
            accelerometerData.last().magnitude
        } else {
            0f
        }
        
        val currentVelocity = if (locationData.size >= 2) {
            val current = locationData.last()
            val previous = locationData[locationData.size - 2]
            val distance = calculateDistance(
                previous.latitude, previous.longitude,
                current.latitude, current.longitude
            )
            val timeDelta = (current.timestamp - previous.timestamp) / 1000f
            if (timeDelta > 0) distance / timeDelta else 0f
        } else {
            0f
        }
        
        return mapOf(
            "accelerometer_data_points" to accelerometerData.size,
            "location_data_points" to locationData.size,
            "current_acceleration" to currentAcceleration,
            "current_velocity" to currentVelocity,
            "baseline_acceleration" to baselineAcceleration,
            "baseline_velocity" to baselineVelocity,
            "movement_variance" to calculateMovementVariance(),
            "anomaly_score" to currentAnomalyScore
        )
    }
}