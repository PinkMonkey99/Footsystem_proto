// Footactivity / BLE 동시 검색

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

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
class FootActivity : ComponentActivity() {

    private val leftDeviceName = "ESP32-S3 BLE left shoes"
    private val rightDeviceName = "ESP32-S3 BLE right shoes"

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
    private var leftFound = false
    private var rightFound = false

    private var isMeasuring by mutableStateOf(false)
    private var isLeftConnected by mutableStateOf(false)
    private var isRightConnected by mutableStateOf(false)

    private val fsrLeft = mutableStateListOf(0, 0, 0, 0, 0)
    private val fsrRight = mutableStateListOf(0, 0, 0, 0, 0)
    private var squatPostureLeft by mutableStateOf("")
    private var squatPostureRight by mutableStateOf("")

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
                        title = { Text("Foot Measurement") },
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

                    SquatPostureDisplay(squatPostureLeft, squatPostureRight)
                    Spacer(modifier = Modifier.height(8.dp))
                    FootImageDisplay(fsrLeft, fsrRight)
                }
            }
        }
    }

    private fun startBleScan() {
        if (isMeasuring) return

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return

        isMeasuring = true
        isLeftConnected = false
        isRightConnected = false
        leftFound = false
        rightFound = false

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                val gattCallback = getGattCallback(name)
                when (name) {
                    leftDeviceName -> if (!leftFound) {
                        leftFound = true
                        leftGatt = result.device.connectGatt(this@FootActivity, false, gattCallback)
                    }
                    rightDeviceName -> if (!rightFound) {
                        rightFound = true
                        rightGatt = result.device.connectGatt(this@FootActivity, false, gattCallback)
                    }
                }
                if (leftFound && rightFound) {
                    scanner.stopScan(this)
                    retryCount = 0
                }
            }
        }

        scanner.startScan(callback)

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isMeasuring) return@postDelayed
            if (!isLeftConnected || !isRightConnected) {
                scanner.stopScan(callback)
                if (retryCount < maxRetry) {
                    retryCount++
                    Log.w("BLE", "⏳ 재시도 $retryCount/$maxRetry")
                    startBleScan()
                } else {
                    Log.e("BLE", "❌ BLE 연결 실패: 최대 재시도 초과")
                    isMeasuring = false
                }
            }
        }, 5000)
    }

    private fun getGattCallback(deviceName: String) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(256)
                runOnUiThread {
                    if (deviceName == leftDeviceName) isLeftConnected = true
                    else if (deviceName == rightDeviceName) isRightConnected = true
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
                runOnUiThread {
                    if (deviceName == leftDeviceName) isLeftConnected = false
                    else if (deviceName == rightDeviceName) isRightConnected = false
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
                    json.optJSONArray("fsr_left")?.let {
                        runOnUiThread {
                            for (i in 0 until it.length()) fsrLeft[i] = it.getInt(i)
                        }
                    }
                    json.optJSONArray("fsr_right")?.let {
                        runOnUiThread {
                            for (i in 0 until it.length()) fsrRight[i] = it.getInt(i)
                        }
                    }
                    val posture = json.optString("squat_posture", "")
                    runOnUiThread {
                        if (gatt.device.name == leftDeviceName) squatPostureLeft = posture
                        else if (gatt.device.name == rightDeviceName) squatPostureRight = posture
                    }
                } catch (e: Exception) {
                    Log.e("BLE", "JSON Parse 오류: ${e.localizedMessage}")
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

        for (i in 0 until fsrLeft.size) fsrLeft[i] = 0
        for (i in 0 until fsrRight.size) fsrRight[i] = 0
        squatPostureLeft = ""
        squatPostureRight = ""
    }
}

@Composable
fun SquatPostureDisplay(squatPostureLeft: String, squatPostureRight: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("왼발 스쿼트 자세: $squatPostureLeft", color = Color.Magenta)
        Text("오른발 스쿼트 자세: $squatPostureRight", color = Color.Magenta)
    }
}

@Composable
fun FootImageDisplay(fsrLeftValues: List<Int>, fsrRightValues: List<Int>) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(id = R.drawable.foot_l),
                contentDescription = "Left Foot Base",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            val leftImages = listOf(
                R.drawable.foot_angle_l_fsr4,
                R.drawable.foot_angle_l_fsr5,
                R.drawable.foot_angle_l_fsr6,
                R.drawable.foot_angle_l_fsr7,
                R.drawable.foot_angle_l_fsr15
            )
            fsrLeftValues.forEachIndexed { index, value ->
                val alpha = (value.coerceIn(0, 5000).toFloat() / 5000f)
                val tint = Color.Red.copy(alpha = alpha)
                Image(
                    painter = painterResource(id = leftImages[index]),
                    contentDescription = "Left FSR $index",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    colorFilter = ColorFilter.tint(tint)
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(id = R.drawable.foot_r),
                contentDescription = "Right Foot Base",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            val rightImages = listOf(
                R.drawable.foot_angle_r_fsr4,
                R.drawable.foot_angle_r_fsr5,
                R.drawable.foot_angle_r_fsr6,
                R.drawable.foot_angle_r_fsr7,
                R.drawable.foot_angle_r_fsr15
            )
            fsrRightValues.forEachIndexed { index, value ->
                val alpha = (value.coerceIn(0, 5000).toFloat() / 5000f)
                val tint = Color.Blue.copy(alpha = alpha)
                Image(
                    painter = painterResource(id = rightImages[index]),
                    contentDescription = "Right FSR $index",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    colorFilter = ColorFilter.tint(tint)
                )
            }
        }
    }
}
