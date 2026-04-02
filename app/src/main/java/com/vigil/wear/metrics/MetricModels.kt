package com.vigil.wear.metrics

enum class MetricProvider(val label: String) {
    SensorSdk("Samsung SDK"),
    AndroidSensors("Android sensors"),
    HealthServices("Health Services"),
    Classifier("Classifier"),
}

enum class MetricKind {
    Raw,
    Derived,
    Event,
    PlatformState,
}

enum class MetricStatus {
    Normal,
    Warning,
    Critical,
    Inactive,
    Unavailable,
}

enum class PermissionState {
    Granted,
    Denied,
    NotRequired,
}

enum class SupportState {
    Supported,
    Unsupported,
    Unknown,
}

data class MetricReading(
    val metricId: String,
    val title: String,
    val provider: MetricProvider,
    val serviceName: String,
    val sourceKey: String,
    val kind: MetricKind,
    val value: String,
    val unit: String = "",
    val status: MetricStatus = MetricStatus.Normal,
    val trend: String = "",
    val classifierContribution: String = "",
    val availability: String = "",
    val permissionState: PermissionState = PermissionState.NotRequired,
    val supportState: SupportState = SupportState.Unknown,
)
