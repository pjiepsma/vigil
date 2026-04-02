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
    private const val READ_HEART_RATE_PERMISSION = "android.permission.health.READ_HEART_RATE"
    private const val READ_OXYGEN_SATURATION_PERMISSION = "android.permission.health.READ_OXYGEN_SATURATION"
    private const val READ_SKIN_TEMPERATURE_PERMISSION = "android.permission.health.READ_SKIN_TEMPERATURE"

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
            addAll(samsungHealthSensorPermissions())
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

    fun samsungHealthSensorPermissions(): List<String> =
        buildList {
            if (Build.VERSION.SDK_INT >= ANDROID_16_API) {
                add(READ_HEART_RATE_PERMISSION)
                add(READ_OXYGEN_SATURATION_PERMISSION)
                add(READ_SKIN_TEMPERATURE_PERMISSION)
            } else {
                add(Manifest.permission.BODY_SENSORS)
            }
        }.distinct()

    fun canUseSamsungHealthSensors(context: Context): Boolean =
        isSamsungWatch(context) &&
            samsungHealthSensorPermissions().all { hasPermission(context, it) }

    fun sensorSdkAvailability(context: Context): ProviderAvailability {
        val missingSamsungPermissions =
            samsungHealthSensorPermissions().filterNot { hasPermission(context, it) }
        val permissionState =
            if (missingSamsungPermissions.isEmpty()) {
                PermissionState.Granted
            } else {
                PermissionState.Denied
            }
        val supportState =
            if (isSamsungWatch(context)) {
                SupportState.Supported
            } else {
                SupportState.Unsupported
            }
        val skinPermissionMissingOnApi36 =
            Build.VERSION.SDK_INT >= ANDROID_16_API &&
                !hasPermission(context, READ_SKIN_TEMPERATURE_PERMISSION)
        return ProviderAvailability(
            permissionState = permissionState,
            supportState = supportState,
            message =
                when {
                    !isSamsungWatch(context) -> "Samsung Galaxy Watch required"
                    permissionState == PermissionState.Denied ->
                        "Grant ${missingSamsungPermissions.joinToString { permissionLabel(it) }} for Samsung metrics"
                    skinPermissionMissingOnApi36 ->
                        "Heart rate ready; grant ${permissionLabel(READ_SKIN_TEMPERATURE_PERMISSION)} for skin temperature"
                    else -> "Samsung Health Sensor SDK available"
                },
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

    private fun isSamsungWatch(context: Context): Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)

    private fun permissionLabel(permission: String): String =
        when (permission) {
            READ_HEART_RATE_PERMISSION -> "Heart rate permission"
            READ_OXYGEN_SATURATION_PERMISSION -> "Oxygen saturation permission"
            READ_SKIN_TEMPERATURE_PERMISSION -> "Skin temperature permission"
            Manifest.permission.BODY_SENSORS -> "Body sensors permission"
            else -> permission.substringAfterLast('.')
        }

    private const val ANDROID_16_API = 36
}
