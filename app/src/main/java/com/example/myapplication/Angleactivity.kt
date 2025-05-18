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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.*
import android.util.Log
import androidx.compose.foundation.background

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
class AngleActivity : ComponentActivity() {

    private val leftDeviceName = "ESP32-S3 BLE Shoe left"
    private val rightDeviceName = "ESP32-S3 BLE right shoe"

    private val leftServiceUUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
    private val leftNotifyUUID = UUID.fromString("abcdef01-1234-5678-1234-56789abcdef0")
    private val leftWriteUUID = UUID.fromString("abcdef02-1234-5678-1234-56789abcdef0")

    private val rightServiceUUID = UUID.fromString("87654321-4321-6789-4321-0fedcba98765")
    private val rightNotifyUUID = UUID.fromString("fedcba01-4321-6789-4321-0fedcba98765")
    private val rightWriteUUID = UUID.fromString("fedcba02-4321-6789-4321-0fedcba98765")

    private var leftGatt: BluetoothGatt? = null
    private var rightGatt: BluetoothGatt? = null

    private var measureJob: Job? = null
    private var isMeasuring by mutableStateOf(false)
    private var isLeftConnected by mutableStateOf(false)
    private var isRightConnected by mutableStateOf(false)

    private var leftRoll by mutableStateOf(0.0)
    private var rightRoll by mutableStateOf(0.0)

    private var leftWriteChar: BluetoothGattCharacteristic? = null
    private var rightWriteChar: BluetoothGattCharacteristic? = null

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
                        title = { Text("Angle Measurement") },
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

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (isLeftConnected) "✅ 왼발 연결됨" else "🔄 왼발 연결 대기 중...",
                        color = if (isLeftConnected) Color.Green else Color.Gray,
                        modifier = Modifier.align(Alignment.Start).padding(start = 16.dp)
                    )
                    Text(
                        text = if (isRightConnected) "✅ 오른발 연결됨" else "🔄 오른발 연결 대기 중...",
                        color = if (isRightConnected) Color.Green else Color.Gray,
                        modifier = Modifier.align(Alignment.Start).padding(start = 16.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    RotatingFootImages(leftRoll = leftRoll, rightRoll = rightRoll)
                }
            }
        }
    }

    private fun startBleScan() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        scanner.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                val gattCallback = getGattCallback(name)
                when (name) {
                    leftDeviceName -> leftGatt = result.device.connectGatt(this@AngleActivity, false, gattCallback)
                    rightDeviceName -> rightGatt = result.device.connectGatt(this@AngleActivity, false, gattCallback)
                }
                if (leftGatt != null && rightGatt != null) scanner.stopScan(this)
            }
        })
    }

    private fun getGattCallback(deviceName: String) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(256)
                runOnUiThread {
                    if (deviceName == leftDeviceName) isLeftConnected = true
                    else if (deviceName == rightDeviceName) isRightConnected = true
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val (svcUUID, notifyUUID, writeUUID) = if (deviceName == leftDeviceName)
                Triple(leftServiceUUID, leftNotifyUUID, leftWriteUUID)
            else
                Triple(rightServiceUUID, rightNotifyUUID, rightWriteUUID)

            val service = gatt.getService(svcUUID) ?: return
            val notifyChar = service.getCharacteristic(notifyUUID) ?: return
            val writeChar = service.getCharacteristic(writeUUID)

            gatt.setCharacteristicNotification(notifyChar, true)
            notifyChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))?.apply {
                value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(this)
            }

            if (deviceName == leftDeviceName) leftWriteChar = writeChar else rightWriteChar = writeChar

            if (leftWriteChar != null && rightWriteChar != null && measureJob == null) {
                measureJob = CoroutineScope(Dispatchers.IO).launch {
                    isMeasuring = true
                    while (isActive) {
                        delay(1000)
                        leftWriteChar?.apply {
                            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            value = "measure".toByteArray()
                            leftGatt?.writeCharacteristic(this)
                        }
                        rightWriteChar?.apply {
                            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            value = "measure".toByteArray()
                            rightGatt?.writeCharacteristic(this)
                        }
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val raw = String(characteristic.value, Charsets.UTF_8)
            val end = raw.indexOf('}') + 1
            if (end > 0) {
                try {
                    val json = JSONObject(raw.substring(0, end))
                    val imu = json.optJSONObject("imu")
                    val roll = imu?.optDouble("roll") ?: return
                    runOnUiThread {
                        if (deviceName == leftDeviceName) leftRoll = roll
                        else if (deviceName == rightDeviceName) rightRoll = roll
                    }
                } catch (e: Exception) {
                    Log.e("BLE", "JSON parse error: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun stopMeasurement() {
        measureJob?.cancel()
        measureJob = null
        isMeasuring = false
        leftGatt?.disconnect(); rightGatt?.disconnect()
        leftGatt?.close(); rightGatt?.close()
        isLeftConnected = false; isRightConnected = false
    }
}

@Composable
fun RotatingFootImages(leftRoll: Double, rightRoll: Double) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 32.dp)
            .background(Color.Black), // ✅ 검정 배경 추가
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "왼발 Roll: %.2f°".format(leftRoll),
                color = Color.Magenta,
                modifier = Modifier.graphicsLayer { rotationZ = 90f }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Image(
                painter = painterResource(id = R.drawable.foot_angle_l),
                contentDescription = "왼발 이미지",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(0.95f) // 가로 너비 조절
                    .aspectRatio(1f) // 가로 세로 비율 1:1 고정
                    .graphicsLayer { rotationZ = 90f } // 회전
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "오른발 Roll: %.2f°".format(rightRoll),
                color = Color.Magenta,
                modifier = Modifier.graphicsLayer { rotationZ = 90f }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Image(
                painter = painterResource(id = R.drawable.foot_angle_r),
                contentDescription = "오른발 이미지",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .aspectRatio(1f)
                    .graphicsLayer { rotationZ = 90f }
            )
        }
    }
}
