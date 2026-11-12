package com.example.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Connection states for Bluetooth HID Mouse.
 */
sealed class HidConnectionState {
    object Idle : HidConnectionState()
    object BluetoothDisabled : HidConnectionState()
    object PermissionRequired : HidConnectionState()
    object ServiceConnecting : HidConnectionState()
    object RegisteredNotConnected : HidConnectionState()
    data class Connecting(val deviceName: String) : HidConnectionState()
    data class Connected(val deviceName: String, val address: String) : HidConnectionState()
    object Disconnected : HidConnectionState()
    data class Error(val message: String) : HidConnectionState()
}

/**
 * Diagnostic metrics for HID transmission.
 */
data class HidMetrics(
    val packetsSent: Long = 0,
    val sendRateHz: Float = 0f,
    val lastPacket: MouseReportPacket = MouseReportPacket(),
    val isHostConnected: Boolean = false,
    val hostDeviceName: String = ""
)

/**
 * Manages the Bluetooth HID Device profile, registering as a standard wireless pointing mouse
 * and transmitting 4-byte report packets at 60Hz~100Hz.
 */
open class BluetoothHidMouseManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "DeskMouse_HID"
        private const val TRANSMISSION_INTERVAL_MS = 12L // ~83 Hz
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null

    private val _connectionState = MutableStateFlow<HidConnectionState>(HidConnectionState.Idle)
    val connectionState: StateFlow<HidConnectionState> = _connectionState.asStateFlow()

    private val _metrics = MutableStateFlow(HidMetrics())
    val metrics: StateFlow<HidMetrics> = _metrics.asStateFlow()

    // Thread-safe button states
    private val leftButtonPressed = AtomicBoolean(false)
    private val rightButtonPressed = AtomicBoolean(false)
    private val middleButtonPressed = AtomicBoolean(false)

    // Thread-safe accumulated displacement
    private val accumulatedDeltaX = AtomicInteger(0)
    private val accumulatedDeltaY = AtomicInteger(0)
    private val accumulatedWheel = AtomicInteger(0)

    private var isAppRegistered = false
    private var transmissionJob: Job? = null
    private var packetsSentCounter = 0L
    private var lastRateCalcTime = System.currentTimeMillis()
    private var packetsInLastSecond = 0

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(TAG, "Bluetooth HID Device profile service connected")
                hidDevice = proxy as? BluetoothHidDevice
                registerHidApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.w(TAG, "Bluetooth HID Device profile service disconnected")
                hidDevice = null
                isAppRegistered = false
                connectedDevice = null
                _connectionState.value = HidConnectionState.Disconnected
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            Log.d(TAG, "onAppStatusChanged: registered=$registered, pluggedDevice=$pluggedDevice")
            isAppRegistered = registered
            if (registered) {
                if (pluggedDevice != null) {
                    onDeviceConnected(pluggedDevice)
                } else {
                    _connectionState.value = HidConnectionState.RegisteredNotConnected
                }
            } else {
                _connectionState.value = HidConnectionState.Error("HID App registration failed or unregistered")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            Log.d(TAG, "onConnectionStateChanged: device=$device, state=$state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (device != null) onDeviceConnected(device)
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    val name = getSafeDeviceName(device)
                    _connectionState.value = HidConnectionState.Connecting(name)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevice = null
                    _connectionState.value = if (isAppRegistered) {
                        HidConnectionState.RegisteredNotConnected
                    } else {
                        HidConnectionState.Disconnected
                    }
                    updateMetrics(isHost = false, hostName = "")
                }
            }
        }
    }

    /**
     * Initializes the Bluetooth HID manager and connects to the Android BluetoothProfile.HID_DEVICE.
     */
    fun initialize() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            _connectionState.value = HidConnectionState.Error("Bluetooth HID Device requires Android 9.0 (API 28) or higher")
            return
        }

        if (bluetoothAdapter == null) {
            _connectionState.value = HidConnectionState.Error("Device does not support Bluetooth")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            _connectionState.value = HidConnectionState.BluetoothDisabled
            return
        }

        if (!hasRequiredPermissions()) {
            _connectionState.value = HidConnectionState.PermissionRequired
            return
        }

        _connectionState.value = HidConnectionState.ServiceConnecting
        val success = bluetoothAdapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        if (!success) {
            _connectionState.value = HidConnectionState.Error("Failed to acquire Bluetooth HID Device profile proxy")
        }

        startTransmissionLoop()
    }

    /**
     * Checks if all necessary Bluetooth runtime permissions are granted.
     */
    fun hasRequiredPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connect = ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val advertise = ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            return connect && advertise
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun registerHidApp() {
        val device = hidDevice ?: return
        if (isAppRegistered) return

        try {
            val sdpSettings = BluetoothHidDeviceAppSdpSettings(
                HidConstants.SDP_NAME,
                HidConstants.SDP_DESCRIPTION,
                HidConstants.SDP_PROVIDER,
                HidConstants.MOUSE_SUBCLASS,
                HidConstants.MOUSE_REPORT_DESCRIPTOR
            )

            val qosSettings = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                800,
                9,
                0,
                11250,
                BluetoothHidDeviceAppQosSettings.MAX
            )

            val executor = Executors.newSingleThreadExecutor()
            val registered = device.registerApp(sdpSettings, qosSettings, null, executor, hidCallback)
            Log.d(TAG, "registerApp returned: $registered")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException registering HID App: ${e.message}")
            _connectionState.value = HidConnectionState.PermissionRequired
        } catch (e: Exception) {
            Log.e(TAG, "Exception registering HID App: ${e.message}")
            _connectionState.value = HidConnectionState.Error("Registration error: ${e.localizedMessage}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun onDeviceConnected(device: BluetoothDevice) {
        connectedDevice = device
        val name = getSafeDeviceName(device)
        val address = try { device.address ?: "Unknown" } catch (e: SecurityException) { "Protected" }
        _connectionState.value = HidConnectionState.Connected(name, address)
        updateMetrics(isHost = true, hostName = name)
        Log.i(TAG, "Connected to HID Host: $name ($address)")
    }

    @SuppressLint("MissingPermission")
    private fun getSafeDeviceName(device: BluetoothDevice?): String {
        if (device == null) return "Unknown"
        return try {
            device.name ?: device.address ?: "Tablet Device"
        } catch (e: SecurityException) {
            "Host Tablet"
        }
    }

    /**
     * Updates physical/simulated mouse button states.
     */
    fun setButtons(left: Boolean, right: Boolean, middle: Boolean = false) {
        leftButtonPressed.set(left)
        rightButtonPressed.set(right)
        middleButtonPressed.set(middle)
    }

    /**
     * Accumulates relative cursor displacements and scroll steps.
     */
    open fun postMovement(deltaX: Int, deltaY: Int, wheel: Int = 0) {
        accumulatedDeltaX.addAndGet(deltaX)
        accumulatedDeltaY.addAndGet(deltaY)
        accumulatedWheel.addAndGet(wheel)
    }

    /**
     * Starts the periodic transmission coroutine loop (~83Hz).
     */
    private fun startTransmissionLoop() {
        transmissionJob?.cancel()
        transmissionJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(TRANSMISSION_INTERVAL_MS)
                sendPendingReport()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendPendingReport() {
        val dx = accumulatedDeltaX.getAndSet(0)
        val dy = accumulatedDeltaY.getAndSet(0)
        val wheel = accumulatedWheel.getAndSet(0)
        val left = leftButtonPressed.get()
        val right = rightButtonPressed.get()
        val middle = middleButtonPressed.get()

        val packet = MouseReportPacket(
            leftButton = left,
            rightButton = right,
            middleButton = middle,
            deltaX = dx,
            deltaY = dy,
            wheel = wheel
        )

        // Only send if there is motion or button pressed or if state changed
        val targetDevice = connectedDevice
        val deviceProxy = hidDevice

        if (targetDevice != null && deviceProxy != null && isAppRegistered) {
            try {
                val reportBytes = packet.encode()
                val success = deviceProxy.sendReport(targetDevice, HidConstants.REPORT_ID_MOUSE, reportBytes)
                if (success) {
                    packetsSentCounter++
                    packetsInLastSecond++
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission error sending report: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending HID report: ${e.message}")
            }
        } else {
            // When disconnected or in simulation, still record packet for UI diagnostics
            if (!packet.isIdle) {
                packetsSentCounter++
                packetsInLastSecond++
            }
        }

        // Calculate rate every second
        val now = System.currentTimeMillis()
        if (now - lastRateCalcTime >= 1000) {
            val rateHz = (packetsInLastSecond * 1000f) / (now - lastRateCalcTime)
            packetsInLastSecond = 0
            lastRateCalcTime = now
            updateMetrics(
                isHost = (connectedDevice != null),
                hostName = getSafeDeviceName(connectedDevice),
                rateHz = rateHz,
                lastPacket = packet
            )
        } else if (!packet.isIdle) {
            updateMetrics(
                isHost = (connectedDevice != null),
                hostName = getSafeDeviceName(connectedDevice),
                lastPacket = packet
            )
        }
    }

    private fun updateMetrics(
        isHost: Boolean,
        hostName: String,
        rateHz: Float = _metrics.value.sendRateHz,
        lastPacket: MouseReportPacket = _metrics.value.lastPacket
    ) {
        _metrics.value = HidMetrics(
            packetsSent = packetsSentCounter,
            sendRateHz = rateHz,
            lastPacket = lastPacket,
            isHostConnected = isHost,
            hostDeviceName = hostName
        )
    }

    /**
     * Connects to a previously bonded/paired Bluetooth device.
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice): Boolean {
        val proxy = hidDevice ?: return false
        return try {
            proxy.connect(device)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException connecting to device: ${e.message}")
            false
        }
    }

    /**
     * Disconnects currently connected host.
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        val dev = connectedDevice ?: return
        val proxy = hidDevice ?: return
        try {
            proxy.disconnect(dev)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException disconnecting: ${e.message}")
        }
    }

    /**
     * Clean up and unregister profile service on shutdown.
     */
    @SuppressLint("MissingPermission")
    fun release() {
        transmissionJob?.cancel()
        val proxy = hidDevice
        if (proxy != null) {
            try {
                if (isAppRegistered) {
                    proxy.unregisterApp()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering HID App: ${e.message}")
            }
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
            hidDevice = null
        }
        isAppRegistered = false
        connectedDevice = null
        _connectionState.value = HidConnectionState.Disconnected
    }
}
