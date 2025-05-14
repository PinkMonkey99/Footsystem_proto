package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.*
import android.util.Log

@OptIn(ExperimentalMaterial3Api::class)
class FootActivity : ComponentActivity() {

    private var bluetoothGatt: BluetoothGatt? = null
    private val espDeviceName = "ESP32-S3 BLE Shoe left"
    private val serviceUUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
    private val notifyCharUUID = UUID.fromString("abcdef01-1234-5678-1234-56789abcdef0")
    private val writeCharUUID = UUID.fromString("abcdef02-1234-5678-1234-56789abcdef0")

    private val fsrValues = mutableStateListOf(0, 0, 0, 0, 0)
    private var squatPosture = mutableStateOf("")
    private var isConnectedState = mutableStateOf(false)
    private var connectedDeviceNameState = mutableStateOf("")
    private var isMeasuring = mutableStateOf(false)
    private var periodicJob: Job? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            if (!needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
                registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
                    .launch(needed)
            }
        }

        setContent {
            MyApplicationTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = { Text("Foot Measurement") },
                        navigationIcon = {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로 가기")
                            }
                        },
                        actions = {
                            Button(onClick = { startBleScan() }, enabled = !isMeasuring.value) { Text("측정시작") }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = { stopMeasurement() }, enabled = isMeasuring.value) { Text("측정중지") }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (isConnectedState.value) "✅ 연결됨: ${connectedDeviceNameState.value}" else "🔄 BLE 연결 대기 중...",
                        color = if (isConnectedState.value) Color.Green else Color.Gray,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val pinList = listOf(4, 5, 6, 7, 15)
                    fsrValues.forEachIndexed { index, value ->
                        Text(
                            text = "FSR 핀 ${pinList[index]}: $value",
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            color = Color.Blue
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "스쿼트 자세: ${squatPosture.value}",
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        color = Color.Magenta
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    FootImageDisplay(fsrValues)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        scanner.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.name == espDeviceName) {
                    if (ContextCompat.checkSelfPermission(
                            this@FootActivity,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        try {
                            bluetoothGatt = result.device.connectGatt(
                                this@FootActivity,
                                false,
                                gattCallback,
                                BluetoothDevice.TRANSPORT_LE
                            )
                        } catch (e: SecurityException) {
                            Log.e("FootBLE", "SecurityException connectGatt: ${e.message}")
                        }
                    } else {
                        Log.w("FootBLE", "Missing BLUETOOTH_CONNECT permission for connectGatt")
                    }
                    scanner.stopScan(this)
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread {
                    isConnectedState.value = true
                    connectedDeviceNameState.value = espDeviceName
                }
                if (ContextCompat.checkSelfPermission(
                        this@FootActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    try {
                        gatt.requestMtu(256)
                    } catch (e: SecurityException) {
                        Log.e("FootBLE", "SecurityException requestMtu: ${e.message}")
                    }
                } else {
                    Log.w("FootBLE", "Missing BLUETOOTH_CONNECT permission for requestMtu")
                }
                bluetoothGatt = gatt
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(serviceUUID) ?: return
            writeCharacteristic = service.getCharacteristic(writeCharUUID)
            val notifyChar = service.getCharacteristic(notifyCharUUID)

            if (ContextCompat.checkSelfPermission(
                    this@FootActivity,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    gatt.setCharacteristicNotification(notifyChar, true)
                    val descriptor = notifyChar.getDescriptor(
                        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                    )
                    descriptor?.let {
                        @Suppress("DEPRECATION")
                        it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        try {
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(it)
                        } catch (e: SecurityException) {
                            Log.e("FootBLE", "SecurityException writeDescriptor: ${e.message}")
                        }
                    }
                } catch (e: SecurityException) {
                    Log.e("FootBLE", "SecurityException setCharacteristicNotification: ${e.message}")
                }
            } else {
                Log.w("FootBLE", "Missing BLUETOOTH_CONNECT permission for notifications")
            }

            periodicJob?.cancel()
            periodicJob = CoroutineScope(Dispatchers.IO).launch {
                runOnUiThread { isMeasuring.value = true }
                while (isActive) {
                    delay(1000)
                    writeCharacteristic?.apply {
                        writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        value = "measure".toByteArray()
                        if (ContextCompat.checkSelfPermission(
                                this@FootActivity,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            try {
                                @Suppress("DEPRECATION")
                                gatt.writeCharacteristic(this)
                            } catch (e: SecurityException) {
                                Log.e("FootBLE", "SecurityException writeCharacteristic: ${e.message}")
                            }
                        } else {
                            Log.w("FootBLE", "Missing BLUETOOTH_CONNECT permission for writeCharacteristic")
                        }
                    }
                }
            }
        }

        @Deprecated("Deprecated in Android")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val raw = try {
                String(characteristic.value, Charsets.UTF_8)
            } catch (e: SecurityException) {
                Log.e("FootBLE", "SecurityException read characteristic: ${e.message}")
                return
            }
            val end = raw.indexOf('}') + 1
            if (end > 0) {
                try {
                    val json = JSONObject(raw.substring(0, end))
                    val arr = json.getJSONArray("fsr")
                    val posture = json.optString("squat_posture", "")
                    runOnUiThread {
                        for (i in 0 until arr.length()) {
                            fsrValues[i] = arr.getInt(i)
                        }
                        squatPosture.value = posture
                    }
                } catch (e: Exception) {
                    Log.e("FootBLE", "JSON parse error: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun stopMeasurement() {
        periodicJob?.cancel()
        periodicJob = null
        runOnUiThread { isMeasuring.value = false }
        bluetoothGatt?.apply {
            try { disconnect() } catch (_: SecurityException) { }
            try { close() } catch (_: SecurityException) { }
        }
        runOnUiThread {
            isConnectedState.value = false
            connectedDeviceNameState.value = ""
        }
    }
}

@Composable
fun FootImageDisplay(fsrValues: List<Int>) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(id = R.drawable.foots_2),
            contentDescription = "Foot Image",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        val fsrImages = listOf(
            R.drawable.foot_l_fsr4,
            R.drawable.foot_l_fsr5,
            R.drawable.foot_l_fsr6,
            R.drawable.foot_l_fsr7,
            R.drawable.foot_l_fsr15
        )

        fsrValues.forEachIndexed { index, value ->
            val alpha = (value.coerceIn(0, 5000).toFloat() / 5000f)
            val tintColor = Color.Red.copy(alpha = alpha)

            Image(
                painter = painterResource(fsrImages[index]),
                contentDescription = "FSR Section $index",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(tintColor)
            )
        }
    }
}
