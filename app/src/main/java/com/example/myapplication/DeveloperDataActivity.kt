package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import java.util.*

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
class DeveloperDataActivity : ComponentActivity() {

    private val leftDeviceName = "ESP32-S3 BLE left shoe"
    private val rightDeviceName = "ESP32-S3 BLE right shoe"

    private val leftServiceUUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
    private val leftNotifyUUID = UUID.fromString("abcdef01-1234-5678-1234-56789abcdef0")
    private val leftWriteUUID = UUID.fromString("abcdef02-1234-5678-1234-56789abcdef0")

    private val rightServiceUUID = UUID.fromString("87654321-4321-6789-4321-0fedcba98765")
    private val rightNotifyUUID = UUID.fromString("fedcba01-4321-6789-4321-0fedcba98765")
    private val rightWriteUUID = UUID.fromString("fedcba02-4321-6789-4321-0fedcba98765")

    private var leftGatt: BluetoothGatt? = null
    private var rightGatt: BluetoothGatt? = null

    private var leftWriteChar: BluetoothGattCharacteristic? = null
    private var rightWriteChar: BluetoothGattCharacteristic? = null

    private var isMeasuring by mutableStateOf(false)
    private var rawJsonLeft by mutableStateOf("왼발 데이터 없음")
    private var rawJsonRight by mutableStateOf("오른발 데이터 없음")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            if (!permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
                registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(permissions)
            }
        }

        setContent {
            MyApplicationTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = { Text("Developer Data View") },
                        navigationIcon = {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        },
                        actions = {
                            Button(onClick = { startBleScan() }, enabled = !isMeasuring) { Text("측정시작") }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = { stopMeasurement() }, enabled = isMeasuring) { Text("측정종료") }
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("왼발 JSON: $rawJsonLeft", modifier = Modifier.padding(16.dp))
                    Text("오른발 JSON: $rawJsonRight", modifier = Modifier.padding(16.dp))
                }
            }
        }
    }

    private fun startBleScan() {
        isMeasuring = true
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                val gattCallback = getGattCallback(name)
                when (name) {
                    leftDeviceName -> leftGatt = result.device.connectGatt(this@DeveloperDataActivity, false, gattCallback)
                    rightDeviceName -> rightGatt = result.device.connectGatt(this@DeveloperDataActivity, false, gattCallback)
                }
            }
        }

        scanner.startScan(callback)

        Handler(Looper.getMainLooper()).postDelayed({
            scanner.stopScan(callback)
        }, 5000)
    }

    private fun stopMeasurement() {
        isMeasuring = false

        leftWriteChar?.apply {
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            value = "stop".toByteArray()
            leftGatt?.writeCharacteristic(this)
        }
        rightWriteChar?.apply {
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            value = "stop".toByteArray()
            rightGatt?.writeCharacteristic(this)
        }

        leftGatt?.disconnect(); rightGatt?.disconnect()
        leftGatt?.close(); rightGatt?.close()
    }

    private fun getGattCallback(deviceName: String) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(256)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val (svcUUID, notifyUUID, writeUUID) = if (gatt.device.name == leftDeviceName)
                Triple(leftServiceUUID, leftNotifyUUID, leftWriteUUID)
            else Triple(rightServiceUUID, rightNotifyUUID, rightWriteUUID)

            val notifyChar = gatt.getService(svcUUID)?.getCharacteristic(notifyUUID) ?: return
            val writeChar = gatt.getService(svcUUID)?.getCharacteristic(writeUUID)

            gatt.setCharacteristicNotification(notifyChar, true)
            notifyChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }

            if (gatt.device.name == leftDeviceName) leftWriteChar = writeChar else rightWriteChar = writeChar

            writeChar?.apply {
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                value = "start".toByteArray()
                gatt.writeCharacteristic(this)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val jsonRaw = String(characteristic.value, Charsets.UTF_8)
            if (gatt.device.name == leftDeviceName) rawJsonLeft = jsonRaw
            else if (gatt.device.name == rightDeviceName) rawJsonRight = jsonRaw
        }
    }
}
