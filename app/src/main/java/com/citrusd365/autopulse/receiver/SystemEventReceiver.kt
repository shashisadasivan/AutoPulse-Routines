package com.citrusd365.autopulse.receiver

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.citrusd365.autopulse.data.RoutineRepository
import com.citrusd365.autopulse.engine.RoutineEngine
import com.citrusd365.autopulse.service.AutomationForegroundService

class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val repo = RoutineRepository.getInstance(context)
        if (!repo.engineEnabled.value) return

        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AutomationForegroundService.startOrUpdate(context)
            }

            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                // Ensure foreground monitor is running
                if (!AutomationForegroundService.isRunning) {
                    AutomationForegroundService.startOrUpdate(context)
                    // Also dispatch directly if the dynamic receiver wasn't active yet
                    extractDevice(intent)?.let { device ->
                        RoutineEngine.onBluetoothDeviceConnected(context, device)
                    }
                }
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                if (!AutomationForegroundService.isRunning) {
                    AutomationForegroundService.startOrUpdate(context)
                    extractDevice(intent)?.let { device ->
                        RoutineEngine.onBluetoothDeviceDisconnected(context, device)
                    }
                }
            }
        }
    }

    private fun extractDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }
}
