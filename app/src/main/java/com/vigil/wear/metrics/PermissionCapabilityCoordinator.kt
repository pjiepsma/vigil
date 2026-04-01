package com.vigil.wear.metrics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat

data class ProviderAvailability(
    val permissionState: PermissionState,
    val supportState: SupportState,
    val message: String,
)

data class SessionPermissionReadiness(
    val requiredMissingPermissions: List<String>,
    val optionalMissingPermissions: List<String>,
) {
    val canStartSession: Boolean
        get() = requiredMissingPermissions.isEmpty()
}

object PermissionCapabilityCoordinator {
    fun launchPermissions(): List<String> = requiredSessionPermissions()

    fun missingLaunchPermissions(context: Context): List<String> =
        launchPermissions().filterNot { hasPermission(context, it) }

    fun requiredSessionPermissions(): List<String> =
        mutableListOf<String>().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }

    fun optionalSessionPermissions(): List<String> =
        mutableListOf<String>().apply {
            add(Manifest.permission.BODY_SENSORS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun missingSessionPermissions(context: Context): List<String> {
        return requiredSessionPermissions().filterNot { hasPermission(context, it) }
    }

    fun missingOptionalPermissions(context: Context): List<String> =
        optionalSessionPermissions().filterNot { hasPermission(context, it) }

    fun sessionPermissionReadiness(context: Context): SessionPermissionReadiness =
        SessionPermissionReadiness(
            requiredMissingPermissions = missingSessionPermissions(context),
            optionalMissingPermissions = missingOptionalPermissions(context),
        )

    fun hasRequiredSessionPermissions(context: Context): Boolean = missingSessionPermissions(context).isEmpty()

    fun canUsePassiveHeartRate(context: Context): Boolean =
        hasPermission(context, Manifest.permission.BODY_SENSORS)

    fun sensorSdkAvailability(context: Context): ProviderAvailability {
        val bodyPermission =
            if (hasPermission(context, Manifest.permission.BODY_SENSORS)) {
                PermissionState.Granted
            } else {
                PermissionState.Denied
            }
        return ProviderAvailability(
            permissionState = bodyPermission,
            supportState = SupportState.Unsupported,
            message = "Samsung Sensor SDK unavailable in this build",
        )
    }

    fun healthServicesAvailability(context: Context): ProviderAvailability {
        val healthPermission =
            if (hasRequiredSessionPermissions(context)) {
                PermissionState.Granted
            } else {
                PermissionState.Denied
            }
        return ProviderAvailability(
            permissionState = healthPermission,
            supportState = SupportState.Supported,
            message =
                if (healthPermission == PermissionState.Granted) {
                    "Available"
                } else {
                    "Activity recognition required"
                },
        )
    }

    fun androidSensorsAvailability(context: Context): ProviderAvailability {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        val pressure = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
        val supported = accel || gyro || pressure
        return ProviderAvailability(
            permissionState = PermissionState.NotRequired,
            supportState = if (supported) SupportState.Supported else SupportState.Unsupported,
            message = if (supported) "Available" else "No compatible sensors",
        )
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
