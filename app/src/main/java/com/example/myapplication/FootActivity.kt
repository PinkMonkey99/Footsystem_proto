package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.*
import org.json.JSONException
import org.json.JSONObject
import java.util.*
import android.util.Log

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
class FootActivity : ComponentActivity() {
    private var bluetoothGatt: BluetoothGatt? = null
    private val espDeviceName = "ESP32-S3 BLE Shoe"
    private val serviceUUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
    private val notifyCharUUID = UUID.fromString("abcdef01-1234-5678-1234-56789abcdef0")
    private val writeCharUUID = UUID.fromString("abcdef02-1234-5678-1234-56789abcdef0")

    private var fsr0Value = mutableStateOf(0)
    private var isConnectedState = mutableStateOf(false)
    private var connectedDeviceNameState = mutableStateOf("")
    private var isMeasuring = mutableStateOf(false)
    private var periodicJob: Job? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 런타임 권한 요청
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
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로 가기")
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

                    Text(
                        text = "FSR[0] 값: ${fsr0Value.value}",
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        color = Color.Blue
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    FootImageDisplay(fsr0Value.value)
                }
            }
        }
    }

    private fun startBleScan() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        scanner.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.name == espDeviceName) {
                    result.device.connectGatt(this@FootActivity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    scanner.stopScan(this)
                }
            }
        })
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread { isConnectedState.value = true }
                bluetoothGatt = gatt
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(serviceUUID) ?: return
            writeCharacteristic = service.getCharacteristic(writeCharUUID)
            val notifyChar = service.getCharacteristic(notifyCharUUID)
            gatt.setCharacteristicNotification(notifyChar, true)
            // Descriptor는 characteristic에서 가져와야 합니다
            val descriptor = notifyChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }

            periodicJob?.cancel()
            periodicJob = CoroutineScope(Dispatchers.IO).launch {
                runOnUiThread { isMeasuring.value = true }
                while (isActive) {
                    delay(1000)
                    writeCharacteristic?.let {
                        it.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        it.value = "measure".toByteArray()
                        gatt.writeCharacteristic(it)
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val raw = String(characteristic.value, Charsets.UTF_8)
            Log.d("BLE", "📥 RAW JSON: $raw")
            val end = raw.indexOf('}') + 1
            if (end > 0) {
                val jsonStr = raw.substring(0, end)
                try {
                    val json = JSONObject(jsonStr)
                    if (json.has("fsr")) {
                        val firstFsr = json.getJSONArray("fsr").getInt(0)
                        Log.d("BLE", "✅ Parsed FSR[0]: $firstFsr")
                        runOnUiThread { fsr0Value.value = firstFsr }
                    }
                } catch (e: JSONException) {
                    Log.e("BLE", "JSON parse error: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun stopMeasurement() {
        periodicJob?.cancel()
        periodicJob = null
        runOnUiThread { isMeasuring.value = false }
        bluetoothGatt?.let {
            it.disconnect()
            it.close()
        }
        runOnUiThread {
            isConnectedState.value = false
            connectedDeviceNameState.value = ""
        }
    }
}

@Composable
fun FootImageDisplay(fsr0: Int) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(id = R.drawable.foots_2),
            contentDescription = "Foot Image", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val color = when {
                fsr0 > 2000 -> Color.Green
                fsr0 > 1000 -> Color.Yellow
                fsr0 > 50   -> Color.Red
                else        -> Color.Gray
            }
            drawCircle(color = color, radius = 40f,
                center = Offset(x = size.width * 0.5f, y = size.height * 0.7f))
        }
    }
}
