package com.example.androidautomate.engine

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.androidautomate.model.PairedBluetoothDeviceInfo

object BluetoothHelper {

    fun hasBluetoothPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(context: Context): List<PairedBluetoothDeviceInfo> {
        if (!hasBluetoothPermission(context)) return emptyList()
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = btManager?.adapter
        val bonded = runCatching { adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())

        return bonded.map { device ->
            val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "Unnamed Device" }
            val address = runCatching { device.address }.getOrNull().orEmpty()
            val majorClass = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
            val isAudioOrCar = majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                CAR_KEYWORDS.any { name.contains(it, ignoreCase = true) }
            val connected = isDeviceConnected(device)

            PairedBluetoothDeviceInfo(
                name = name,
                address = address,
                isAudioOrCarClass = isAudioOrCar,
                isCurrentlyConnected = connected
            )
        }.sortedWith(
            compareByDescending<PairedBluetoothDeviceInfo> { it.isCurrentlyConnected }
                .thenByDescending { it.isAudioOrCarClass }
                .thenBy { it.name.lowercase() }
        )
    }

    @SuppressLint("MissingPermission")
    fun getDeviceNameSafely(context: Context, device: BluetoothDevice?): String {
        if (device == null || !hasBluetoothPermission(context)) return "Unknown Device"
        return runCatching { device.name }.getOrNull().orEmpty().ifBlank { device.address ?: "Unknown Device" }
    }

    fun isDeviceConnected(device: BluetoothDevice): Boolean {
        return runCatching {
            val method = device.javaClass.getMethod("isConnected")
            method.invoke(device) as? Boolean ?: false
        }.getOrDefault(false)
    }

    /**
     * Checks whether a given BluetoothDevice matches a Routine's target MAC address or name filter.
     */
    @SuppressLint("MissingPermission")
    fun matchesRoutineTarget(
        context: Context,
        device: BluetoothDevice,
        targetAddress: String,
        targetName: String
    ): Boolean {
        val deviceAddress = runCatching { device.address }.getOrNull().orEmpty()
        val deviceName = getDeviceNameSafely(context, device)

        val cleanTargetAddr = targetAddress.trim()
        val cleanTargetName = targetName.trim()

        // If user specified MAC address(es), check MAC match first
        if (cleanTargetAddr.isNotBlank() && cleanTargetAddr != "*") {
            val allowedAddresses = cleanTargetAddr.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (allowedAddresses.any { it.equals(deviceAddress, ignoreCase = true) }) {
                return true
            }
            // If MAC was explicitly set and didn't match, return false
            return false
        }

        // If user specified a device name or substring (e.g. "My Car", "Subaru", "HandsFree")
        if (cleanTargetName.isNotBlank() && cleanTargetName != "*") {
            return deviceName.contains(cleanTargetName, ignoreCase = true)
        }

        // Wildcard: any audio/car Bluetooth device
        return true
    }

    private val CAR_KEYWORDS = listOf(
        "car", "auto", "sync", "uconnect", "handsfree", "hands-free", "mmi", "bmw", "mercedes",
        "audi", "vw", "volkswagen", "toyota", "honda", "mazda", "subaru", "ford", "chevy",
        "hyundai", "kia", "nissan", "tesla", "volvo", "porsche", "lexus", "acura", "jeep",
        "pioneer", "kenwood", "alpine", "sony", "jvc", "obd", "carplay", "headunit"
    )
}
