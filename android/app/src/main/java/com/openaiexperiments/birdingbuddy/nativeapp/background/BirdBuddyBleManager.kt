package com.openaiexperiments.birdingbuddy.nativeapp.background

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BirdBuddyBleManager(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onBleStatus(connected: Boolean, status: String, deviceName: String? = null)
        fun onBlePacket(packet: ByteArray)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
        manager?.adapter
    }

    private var scanner: BluetoothLeScanner? = null
    private var scanJob: Job? = null
    private var reconnectJob: Job? = null
    private var gatt: BluetoothGatt? = null
    private var subscribedCharacteristic: BluetoothGattCharacteristic? = null

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return
                connectToDevice(device)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                val device = results?.firstOrNull()?.device ?: return
                connectToDevice(device)
            }

            override fun onScanFailed(errorCode: Int) {
                listener.onBleStatus(false, "BLE scan failed ($errorCode).")
                scheduleReconnect()
            }
        }

    private val gattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    closeGatt(gatt)
                    listener.onBleStatus(false, "BLE connection error ($status). Retrying...")
                    scheduleReconnect()
                    return
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        val name = gatt.device.name ?: gatt.device.address
                        listener.onBleStatus(true, "Connected to $name. Discovering services...", name)
                        gatt.discoverServices()
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        closeGatt(gatt)
                        listener.onBleStatus(false, "BLE device disconnected. Retrying...")
                        scheduleReconnect()
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onBleStatus(false, "Service discovery failed ($status). Retrying...")
                    closeGatt(gatt)
                    scheduleReconnect()
                    return
                }

                val service = gatt.getService(BirdBuddyBleProtocol.SERVICE_UUID)
                if (service == null) {
                    listener.onBleStatus(false, "Service UUID not found on BLE device.")
                    closeGatt(gatt)
                    scheduleReconnect()
                    return
                }

                val characteristic =
                    service.getCharacteristic(BirdBuddyBleProtocol.MESSAGE_CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    listener.onBleStatus(false, "Message characteristic UUID not found.")
                    closeGatt(gatt)
                    scheduleReconnect()
                    return
                }

                subscribeToNotifications(gatt, characteristic)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val bytes = characteristic.value ?: return
                if (bytes.isEmpty()) {
                    return
                }
                listener.onBlePacket(bytes)
            }
        }

    fun start() {
        reconnectJob?.cancel()

        if (!hasBlePermissions()) {
            listener.onBleStatus(false, "BLE permissions missing. Grant BLUETOOTH_SCAN/CONNECT.")
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            listener.onBleStatus(false, "Bluetooth adapter unavailable on this device.")
            return
        }

        if (!adapter.isEnabled) {
            listener.onBleStatus(false, "Bluetooth is off. Turn it on to connect Bird Buddy remote.")
            return
        }

        startScan()
    }

    fun retryNow() {
        stop()
        start()
    }

    fun stop() {
        scanJob?.cancel()
        scanJob = null

        reconnectJob?.cancel()
        reconnectJob = null

        runCatching { scanner?.stopScan(scanCallback) }
        scanner = null

        gatt?.let { closeGatt(it) }
        gatt = null
        subscribedCharacteristic = null
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    private fun startScan() {
        if (!hasBlePermissions()) {
            listener.onBleStatus(false, "BLE permissions missing. Grant BLUETOOTH_SCAN/CONNECT.")
            return
        }

        val adapter = bluetoothAdapter ?: run {
            listener.onBleStatus(false, "Bluetooth adapter unavailable on this device.")
            return
        }

        scanner = adapter.bluetoothLeScanner
        val localScanner = scanner
        if (localScanner == null) {
            listener.onBleStatus(false, "BLE scanner unavailable.")
            return
        }

        val filter =
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BirdBuddyBleProtocol.SERVICE_UUID))
                .build()

        val settings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

        listener.onBleStatus(false, "Scanning for Bird Buddy BLE device...")
        runCatching {
            localScanner.startScan(listOf(filter), settings, scanCallback)
        }.onFailure {
            listener.onBleStatus(false, "Failed to start BLE scan: ${it.message}")
            scheduleReconnect()
            return
        }

        scanJob?.cancel()
        scanJob =
            scope.launch {
                delay(15_000)
                runCatching { localScanner.stopScan(scanCallback) }
                listener.onBleStatus(false, "BLE device not found. Retrying scan...")
                scheduleReconnect()
            }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        scanJob?.cancel()
        scanJob = null

        runCatching { scanner?.stopScan(scanCallback) }

        val name = device.name ?: device.address
        listener.onBleStatus(false, "Connecting to $name...", name)

        gatt?.let { closeGatt(it) }
        gatt = null

        val connectedGatt =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }

        gatt = connectedGatt
    }

    private fun subscribeToNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        subscribedCharacteristic = characteristic

        val notifyEnabled = gatt.setCharacteristicNotification(characteristic, true)
        if (!notifyEnabled) {
            listener.onBleStatus(false, "Failed to enable BLE notifications. Retrying...")
            closeGatt(gatt)
            scheduleReconnect()
            return
        }

        val descriptor: BluetoothGattDescriptor? =
            characteristic.getDescriptor(BirdBuddyBleProtocol.CLIENT_CONFIG_DESCRIPTOR_UUID)

        if (descriptor == null) {
            listener.onBleStatus(false, "BLE CCC descriptor missing. Retrying...")
            closeGatt(gatt)
            scheduleReconnect()
            return
        }

        val writeStatus =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            writeStatus != BluetoothStatusCodes.SUCCESS
        ) {
            listener.onBleStatus(false, "BLE descriptor write failed ($writeStatus). Retrying...")
            closeGatt(gatt)
            scheduleReconnect()
            return
        }

        val name = gatt.device.name ?: gatt.device.address
        listener.onBleStatus(true, "Subscribed to BLE messages from $name.", name)
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob =
            scope.launch {
                delay(3_000)
                start()
            }
    }

    private fun closeGatt(gatt: BluetoothGatt) {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        if (this.gatt == gatt) {
            this.gatt = null
        }
    }

    private fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }

        val scanGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        val connectGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        return scanGranted && connectGranted
    }
}
