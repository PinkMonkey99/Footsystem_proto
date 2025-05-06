package com.example.myapplication

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.*
import java.util.*
import android.util.Log
import org.json.JSONObject

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

        val permissionsLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.values.all { it }) {
                // 초기에는 연결 안 함
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        setContent {
            MyApplicationTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = { finish() },
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "뒤로 가기"
                            )
                        }
                        Row(modifier = Modifier.padding(end = 16.dp, top = 16.dp)) {
                            Button(onClick = { startBleScan() }, enabled = !isMeasuring.value) {
                                Text("측정시작")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = { stopMeasurement() }, enabled = isMeasuring.value) {
                                Text("측정중지")
                            }
                        }
                    }

                    Text(
                        text = if (isConnectedState.value)
                            "✅ 연결됨: ${connectedDeviceNameState.value}"
                        else
                            "🔄 BLE 연결 대기 중...",
                        color = if (isConnectedState.value) Color.Green else Color.Gray,
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .align(Alignment.CenterHorizontally)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "FSR[0] 값: ${fsr0Value.value}",
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        color = Color.Blue
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Log.d("UI", "🖼️ setContent 내부 fsr0 = ${fsr0Value.value}")

                    FootImageDisplay(fsr0Value.value)
                }
            }
        }
    }

    private fun startBleScan() {
        Log.d("BLE", "🔍 BLE 스캔 시작됨")
        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.name == espDeviceName) {
                    if (ActivityCompat.checkSelfPermission(this@FootActivity, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
                    scanner.stopScan(this)
                    if (ActivityCompat.checkSelfPermission(this@FootActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        return
                    }
                    connectedDeviceNameState.value = result.device.name ?: "알 수 없음"
                    bluetoothGatt = result.device.connectGatt(this@FootActivity, false, object : BluetoothGattCallback() {
                        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                            if (newState == BluetoothProfile.STATE_CONNECTED) {
                                if (ActivityCompat.checkSelfPermission(this@FootActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
                                isConnectedState.value = true
                                gatt.discoverServices()
                            }
                        }

                        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                            val service = gatt.getService(serviceUUID)
                            writeCharacteristic = service.getCharacteristic(writeCharUUID)
                            val notifyChar = service.getCharacteristic(notifyCharUUID)

                            if (ActivityCompat.checkSelfPermission(this@FootActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                return
                            }

                            gatt.setCharacteristicNotification(notifyChar, true)
                            val descriptor = notifyChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            gatt.writeDescriptor(descriptor)

                            periodicJob?.cancel()
                            periodicJob = CoroutineScope(Dispatchers.IO).launch {
                                isMeasuring.value = true
                                while (isActive) {
                                    delay(1000)
                                    if (ActivityCompat.checkSelfPermission(this@FootActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) continue
                                    writeCharacteristic?.let {
                                        Log.d("BLE", "📤 Android → ESP32: measure 전송 시도")
                                        it.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                        it.value = "measure".toByteArray()
                                        gatt.writeCharacteristic(it)
                                    }
                                }
                            }
                        }

                        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                            val value = characteristic.value.toString(Charsets.UTF_8)
                            Log.d("BLE", "📥 수신된 데이터: $value")
                            try {
                                val jsonEndIndex = value.indexOf('}') + 1
                                if (jsonEndIndex > 0) {
                                    val validJson = value.substring(0, jsonEndIndex)
                                    if (validJson.startsWith("{")) {
                                        val json = JSONObject(validJson)
                                        if (json.has("fsr")) {
                                            val fsrArray = json.getJSONArray("fsr")
                                            val firstFsrValue = fsrArray.getInt(0)
                                            Log.d("BLE", "✅ FSR[0] 값 업데이트: $firstFsrValue")
                                            fsr0Value.value = firstFsrValue
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("BLE", "❌ JSON 파싱 오류: ${e.localizedMessage}")
                            }
                        }
                    }, BluetoothDevice.TRANSPORT_LE)
                }
            }
        }
        scanner.startScan(scanCallback)
    }

    private fun stopMeasurement() {
        periodicJob?.cancel()
        periodicJob = null
        isMeasuring.value = false
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
        isConnectedState.value = false
        connectedDeviceNameState.value = ""
    }
}

@Composable
fun FootImageDisplay(fsr0: Int) {
    Log.d("UI", "🖼️ FootImageDisplay 호출됨, fsr0 = $fsr0")
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.foots_2),
            contentDescription = "Foot Image",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val color = when {
                fsr0 > 700 -> Color.Green
                fsr0 > 300 -> Color.Yellow
                fsr0 > 50  -> Color.Red
                else       -> Color.Gray
            }

            drawCircle(
                color = color,
                radius = 40f,
                center = Offset(x = size.width * 0.5f, y = size.height * 0.7f)
            )
        }
    }
}
