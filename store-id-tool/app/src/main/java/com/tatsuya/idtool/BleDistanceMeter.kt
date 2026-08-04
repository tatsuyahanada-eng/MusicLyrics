package com.tatsuya.idtool

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class BleDistanceMeter(private val context: Context) {

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED, ERROR }

    data class ScannedDevice(val name: String, val address: String, val device: BluetoothDevice)

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _devices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val devices: StateFlow<List<ScannedDevice>> = _devices

    private val _lastValue = MutableStateFlow<Float?>(null)
    val lastValue: StateFlow<Float?> = _lastValue

    private val _connectedName = MutableStateFlow("")
    val connectedName: StateFlow<String> = _connectedName

    private var gatt: BluetoothGatt? = null
    private val foundAddresses = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())

    private val CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val stopRunnable = Runnable { stopScan() }

    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val n = d.name ?: return
            if (d.address in foundAddresses) return
            foundAddresses.add(d.address)
            _devices.value = _devices.value + ScannedDevice(n, d.address, d)
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run { _state.value = State.ERROR; return }
        if (!adapter.isEnabled) { _state.value = State.ERROR; return }
        val sc = adapter.bluetoothLeScanner ?: run { _state.value = State.ERROR; return }
        foundAddresses.clear()
        _devices.value = emptyList()
        _state.value = State.SCANNING
        sc.startScan(scanCb)
        handler.postDelayed(stopRunnable, 10_000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        handler.removeCallbacks(stopRunnable)
        try {
            BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (_: Exception) {}
        if (_state.value == State.SCANNING) _state.value = State.IDLE
    }

    @SuppressLint("MissingPermission")
    fun connect(device: ScannedDevice) {
        stopScan()
        _state.value = State.CONNECTING
        _connectedName.value = device.name
        gatt = device.device.connectGatt(context, false, gattCb)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        handler.removeCallbacks(stopRunnable)
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        _state.value = State.IDLE
        _connectedName.value = ""
        _lastValue.value = null
    }

    @SuppressLint("MissingPermission")
    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else {
                _state.value = State.IDLE
                _connectedName.value = ""
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { _state.value = State.ERROR; return }
            var found = false
            for (svc in g.services) {
                for (ch in svc.characteristics) {
                    if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        g.setCharacteristicNotification(ch, true)
                        val desc = ch.getDescriptor(CCC)
                        if (desc != null) {
                            if (Build.VERSION.SDK_INT >= 33) {
                                g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                g.writeDescriptor(desc)
                            }
                        }
                        found = true
                        break
                    }
                }
                if (found) break
            }
            _state.value = if (found) State.CONNECTED else State.ERROR
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            parseData(ch.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            parseData(value)
        }
    }

    private fun parseData(data: ByteArray?) {
        if (data == null || data.isEmpty()) return
        val ascii = String(data, Charsets.US_ASCII).trim()
        val match = Regex("""(\d+\.?\d*)""").find(ascii)
        if (match != null) {
            val v = match.groupValues[1].toFloatOrNull()
            if (v != null && v > 0f && v < 100000f) {
                _lastValue.value = v
                return
            }
        }
    }
}
