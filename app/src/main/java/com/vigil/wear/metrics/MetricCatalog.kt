package com.vigil.wear.metrics

import com.vigil.wear.session.SessionMetricsSnapshot

object MetricCatalog {
    fun fromSnapshot(
        snapshot: SessionMetricsSnapshot,
        sensorSdk: ProviderAvailability,
        androidSensors: ProviderAvailability,
        healthServices: ProviderAvailability,
    ): List<MetricReading> {
        return listOf(
            MetricReading(
                metricId = "heart_rate",
                title = "Heart rate",
                provider = MetricProvider.SensorSdk,
                serviceName = "Samsung Health Sensor SDK",
                sourceKey = "HealthTrackerType.HEART_RATE_CONTINUOUS",
                kind = MetricKind.Raw,
                value = snapshot.heartRateBpm?.toString() ?: "--",
                unit = "bpm",
                status = numericStatus(snapshot.heartRateBpm?.toFloat(), 55f, 145f),
                classifierContribution = "Effort/rest discriminator",
                availability = samsungAvailability(snapshot, sensorSdk),
                permissionState = sensorSdk.permissionState,
                supportState = sensorSdk.supportState,
            ),
            MetricReading(
                metricId = "hrv",
                title = "HRV (RMSSD)",
                provider = MetricProvider.SensorSdk,
                serviceName = "Samsung Health Sensor SDK",
                sourceKey = "HeartRateSet.IBI_LIST",
                kind = MetricKind.Derived,
                value = snapshot.hrvRmssdMs?.let { "%.0f".format(it) } ?: "--",
                unit = "ms",
                status = numericStatus(snapshot.hrvRmssdMs, 20f, 90f),
                classifierContribution = "Recovery / arousal context",
                availability = samsungAvailability(snapshot, sensorSdk),
                permissionState = sensorSdk.permissionState,
                supportState = sensorSdk.supportState,
            ),
            MetricReading(
                metricId = "accelerometer",
                title = "Accelerometer",
                provider = MetricProvider.AndroidSensors,
                serviceName = "Android Sensor Manager",
                sourceKey = "Sensor.TYPE_ACCELEROMETER",
                kind = MetricKind.Raw,
                value = snapshot.accelerometerMagnitudeG?.let { "%.3f".format(it) } ?: "--",
                unit = "g",
                status = numericStatus(snapshot.accelerometerMagnitudeG, 0.02f, 0.8f),
                classifierContribution = "Stillness and movement intensity",
                availability = androidSensors.message,
                permissionState = androidSensors.permissionState,
                supportState = androidSensors.supportState,
            ),
            MetricReading(
                metricId = "posture",
                title = "Posture/orientation",
                provider = MetricProvider.AndroidSensors,
                serviceName = "Android Sensor Manager",
                sourceKey = "Sensor.TYPE_GYROSCOPE (derived)",
                kind = MetricKind.Derived,
                value = snapshot.posture ?: "--",
                unit = "",
                status = MetricStatus.Normal,
                classifierContribution = "Sleep vs awake posture context",
                availability = androidSensors.message,
                permissionState = androidSensors.permissionState,
                supportState = androidSensors.supportState,
            ),
            MetricReading(
                metricId = "skin_temp",
                title = "Skin temperature",
                provider = MetricProvider.SensorSdk,
                serviceName = "Samsung Health Sensor SDK",
                sourceKey = "HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS",
                kind = MetricKind.Raw,
                value = snapshot.skinTempC?.let { "%.1f".format(it) } ?: "--",
                unit = "C",
                status = numericStatus(snapshot.skinTempC, 35.8f, 37.5f),
                classifierContribution = "Low-weight context",
                availability = samsungAvailability(snapshot, sensorSdk),
                permissionState = sensorSdk.permissionState,
                supportState = sensorSdk.supportState,
            ),
            MetricReading(
                metricId = "watch7_provider",
                title = "Watch 7 sensor stack",
                provider = MetricProvider.SensorSdk,
                serviceName = "Samsung Health Sensor SDK",
                sourceKey = "Local AAR + Health Platform",
                kind = MetricKind.PlatformState,
                value =
                    when {
                        sensorSdk.supportState == SupportState.Unsupported -> "Unavailable"
                        sensorSdk.permissionState == PermissionState.Denied -> "Permission needed"
                        snapshot.platformUserState?.contains("data flowing", ignoreCase = true) == true -> "Connected"
                        snapshot.platformUserState?.contains("no sensor samples", ignoreCase = true) == true -> "Waiting for samples"
                        else -> "Connected"
                    },
                status =
                    when {
                        sensorSdk.supportState == SupportState.Unsupported -> MetricStatus.Unavailable
                        sensorSdk.permissionState == PermissionState.Denied -> MetricStatus.Warning
                        snapshot.platformUserState?.contains("no sensor samples", ignoreCase = true) == true -> MetricStatus.Warning
                        else -> MetricStatus.Normal
                    },
                classifierContribution = "Provider readiness",
                availability = samsungAvailability(snapshot, sensorSdk),
                permissionState = sensorSdk.permissionState,
                supportState = sensorSdk.supportState,
            ),
            MetricReading(
                metricId = "classifier",
                title = "Classifier summary",
                provider = MetricProvider.Classifier,
                serviceName = "Vigil classifier",
                sourceKey = "Weighted threshold scoring",
                kind = MetricKind.Derived,
                value = snapshot.classifiedState?.name ?: "Unknown",
                unit = snapshot.modeAlignment?.name ?: "",
                status = when {
                    snapshot.alertReason != null -> MetricStatus.Warning
                    else -> MetricStatus.Normal
                },
                trend = "Confidence ${snapshot.confidence?.let { "%.2f".format(it) } ?: "--"}",
                classifierContribution = snapshot.reasons.joinToString(" • "),
                availability = "Available",
                permissionState = PermissionState.NotRequired,
                supportState = SupportState.Supported,
            ),
        ).filterNot { reading ->
            reading.metricId == "watch7_provider" &&
                sensorSdk.supportState == SupportState.Supported &&
                sensorSdk.permissionState == PermissionState.Granted
        }
    }

    private fun numericStatus(value: Float?, warnAt: Float, highAt: Float): MetricStatus {
        val v = value ?: return MetricStatus.Unavailable
        return when {
            v > highAt -> MetricStatus.Critical
            v > warnAt -> MetricStatus.Warning
            else -> MetricStatus.Normal
        }
    }

    private fun samsungAvailability(
        snapshot: SessionMetricsSnapshot,
        sensorSdk: ProviderAvailability,
    ): String {
        if (sensorSdk.supportState == SupportState.Unsupported) return sensorSdk.message
        if (sensorSdk.permissionState == PermissionState.Denied) return sensorSdk.message
        val providerState = snapshot.platformUserState
        return if (!providerState.isNullOrBlank()) providerState else sensorSdk.message
    }
}
