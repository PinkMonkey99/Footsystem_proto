// AngleActivity / BLE 순차 연결 구조로 수정
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import org.json.JSONObject
import java.util.*

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
    private var leftWriteChar: BluetoothGattCharacteristic? = null
    private var rightWriteChar: BluetoothGattCharacteristic? = null

    private var retryCount = 0
    private val maxRetry = 3
    private var isLeftConnecting = false
    private var isRightConnecting = false
    private var leftFound = false
    private var rightFound = false

    private var isMeasuring by mutableStateOf(false)
    private var isLeftConnected by mutableStateOf(false)
    private var isRightConnected by mutableStateOf(false)

    private var leftRoll by mutableStateOf(0.0)
    private var rightRoll by mutableStateOf(0.0)

    private lateinit var scanCallback: ScanCallback

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

        isMeasuring = true
        isLeftConnected = false
        isRightConnected = false
        leftFound = false
        rightFound = false

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                val device = result.device

                if (name == leftDeviceName && !leftFound) {
                    leftFound = true
                    isLeftConnecting = true
                    leftGatt = device.connectGatt(this@AngleActivity, false, getGattCallback(name))
                    Log.d("BLE", "왼발 장치 연결 시도")
                } else if (name == rightDeviceName && !rightFound && isLeftConnected) {
                    rightFound = true
                    isRightConnecting = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        rightGatt = device.connectGatt(this@AngleActivity, false, getGattCallback(name))
                        Log.d("BLE", "오른발 장치 연결 시도")
                    }, 500)
                }

                if (isLeftConnected && isRightConnected) {
                    scanner.stopScan(scanCallback)
                    Log.d("BLE", "✅ 양쪽 모두 연결 완료 → 스캔 종료")
                    retryCount = 0
                }
            }
        }

        scanner.startScan(scanCallback)

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isLeftConnected || !isRightConnected) {
                scanner.stopScan(scanCallback)
                if (retryCount < maxRetry) {
                    retryCount++
                    Log.w("BLE", "⏱️ 연결 실패, ${retryCount}번째 재시도")
                    startBleScan()
                } else {
                    Log.e("BLE", "❌ 연결 실패: 최대 재시도 초과")
                }
            }
        }, 5000)
    }

    private fun getGattCallback(deviceName: String) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt.requestMtu(256)
                    runOnUiThread {
                        if (deviceName == leftDeviceName) isLeftConnected = true
                        else if (deviceName == rightDeviceName) isRightConnected = true
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    gatt.close()
                    runOnUiThread {
                        if (deviceName == leftDeviceName) isLeftConnected = false
                        else if (deviceName == rightDeviceName) isRightConnected = false
                    }
                    Log.d("BLE", "🔌 ${gatt.device.name} 연결 해제됨")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val (svcUUID, notifyUUID, writeUUID) = if (gatt.device.name == leftDeviceName)
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

            if (gatt.device.name == leftDeviceName) leftWriteChar = writeChar else rightWriteChar = writeChar

            // start 명령 전송
            writeChar?.apply {
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                value = "start".toByteArray()
                gatt.writeCharacteristic(this)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val raw = String(characteristic.value, Charsets.UTF_8)
            val end = raw.indexOf('}') + 1
            if (end > 0) {
                try {
                    val json = JSONObject(raw.substring(0, end))
                    val roll = json.optDouble("roll", Double.NaN)
                    if (!roll.isNaN()) {
                        runOnUiThread {
                            if (gatt.device.name == leftDeviceName) leftRoll = roll
                            else if (gatt.device.name == rightDeviceName) rightRoll = roll
                        }
                    }
                } catch (e: Exception) {
                    Log.e("BLE", "JSON 파싱 오류: ${e.localizedMessage}")
                }
            }
        }
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
        isLeftConnected = false; isRightConnected = false
    }
}

@Composable
fun RotatingFootImages(leftRoll: Double, rightRoll: Double) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 32.dp)
            .background(Color.Black),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("왼발 Roll: %.2f°".format(leftRoll), color = Color.Magenta)
            Spacer(modifier = Modifier.width(8.dp))
            Image(
                painter = painterResource(id = R.drawable.foot_angle_l),
                contentDescription = "왼발 이미지",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .aspectRatio(1f)
                    .graphicsLayer { rotationZ = leftRoll.toFloat() }
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("오른발 Roll: %.2f°".format(rightRoll), color = Color.Magenta)
            Spacer(modifier = Modifier.width(8.dp))
            Image(
                painter = painterResource(id = R.drawable.foot_angle_r),
                contentDescription = "오른발 이미지",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .aspectRatio(1f)
                    .graphicsLayer { rotationZ = rightRoll.toFloat() }
            )
        }
    }
}
