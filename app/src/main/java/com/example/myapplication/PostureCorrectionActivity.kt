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
class PostureCorrectionActivity : ComponentActivity() {

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

    private var isMeasuring by mutableStateOf(false)
    private var isLeftConnected by mutableStateOf(false)
    private var isRightConnected by mutableStateOf(false)

    private val fsrLeft = mutableStateListOf(0, 0, 0, 0, 0)
    private val fsrRight = mutableStateListOf(0, 0, 0, 0, 0)
    private var yawLeft by mutableStateOf(0.0)
    private var yawRight by mutableStateOf(0.0)
    private var squatPostureLeft by mutableStateOf("")
    private var squatPostureRight by mutableStateOf("")

    private lateinit var scanCallback: ScanCallback

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

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

                    // 상단 앱바 + 측정 버튼 2개
                    Column {
                        TopAppBar(
                            title = { Text("Posture Correction") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            },
                            actions = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Button(
                                        onClick = { startBleScan() },
                                        enabled = !isMeasuring
                                    ) {
                                        Text("측정시작")
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Button(
                                        onClick = { stopMeasurement() },
                                        enabled = isMeasuring
                                    ) {
                                        Text("측정종료")
                                    }
                                }
                            }
                        )

                        // 왼발/오른발 연결 상태 + Reset 버튼 (같은 줄에 배치)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = if (isLeftConnected) "✅ 왼발 연결됨" else "🔄 왼발 연결 대기 중...",
                                    color = if (isLeftConnected) Color.Green else Color.Gray
                                )
                                Text(
                                    text = if (isRightConnected) "✅ 오른발 연결됨" else "🔄 오른발 연결 대기 중...",
                                    color = if (isRightConnected) Color.Green else Color.Gray
                                )
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            Button(onClick = {
                                sendResetCommand()
                            }) {
                                Text("Reset")
                            }
                        }
                    }

                    // 발 센서 시각화 및 자세 텍스트 영역
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(0.3f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Column(
                                    modifier = Modifier.graphicsLayer(rotationZ = 90f),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text("왼발 Yaw: %.2f°".format(yawLeft), color = Color.Magenta)
                                    Text("왼발 스쿼트 자세: $squatPostureLeft", color = Color.Magenta)
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .weight(0.7f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.Center
                            ) {
                                FootOverlayWithRotation(fsrLeft, yawLeft, squatPostureLeft, isLeft = true)
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(0.3f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Column(
                                    modifier = Modifier.graphicsLayer(rotationZ = 90f),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text("오른발 Yaw: %.2f°".format(yawRight), color = Color.Magenta)
                                    Text("오른발 스쿼트 자세: $squatPostureRight", color = Color.Magenta)
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .weight(0.7f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.Center
                            ) {
                                FootOverlayWithRotation(fsrRight, yawRight, squatPostureRight, isLeft = false)
                            }
                        }
                    }
                }
            }
        }


    }

    private fun sendResetCommand() {
        val resetCommand = "reset".toByteArray()

        leftWriteChar?.apply {
            value = resetCommand
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            leftGatt?.writeCharacteristic(this)
        }

        rightWriteChar?.apply {
            value = resetCommand
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            rightGatt?.writeCharacteristic(this)
        }
    }

    private fun startBleScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        isMeasuring = true
        val foundDevices = mutableSetOf<String>()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    val name = device.name ?: return
                    if (name == leftDeviceName && !isLeftConnected && !foundDevices.contains(name)) {
                        foundDevices.add(name)
                        leftGatt = device.connectGatt(this@PostureCorrectionActivity, false, getGattCallback(name))
                    } else if (name == rightDeviceName && !isRightConnected && !foundDevices.contains(name)) {
                        foundDevices.add(name)
                        rightGatt = device.connectGatt(this@PostureCorrectionActivity, false, getGattCallback(name))
                    }

                    if (foundDevices.contains(leftDeviceName) && foundDevices.contains(rightDeviceName)) {
                        scanner.stopScan(this)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e("BLE", "Scan failed with error code $errorCode")
            }
        }

        scanner.startScan(scanCallback)
    }

    private fun getGattCallback(deviceName: String) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(256)
                runOnUiThread {
                    if (deviceName == leftDeviceName) isLeftConnected = true else isRightConnected = true
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
                runOnUiThread {
                    if (deviceName == leftDeviceName) isLeftConnected = false else isRightConnected = false
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val (svcUUID, notifyUUID, writeUUID) = if (gatt.device.name == leftDeviceName)
                Triple(leftServiceUUID, leftNotifyUUID, leftWriteUUID)
            else Triple(rightServiceUUID, rightNotifyUUID, rightWriteUUID)

            val service = gatt.getService(svcUUID) ?: return
            val notifyChar = service.getCharacteristic(notifyUUID) ?: return
            val writeChar = service.getCharacteristic(writeUUID)

            gatt.setCharacteristicNotification(notifyChar, true)
            notifyChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))?.apply {
                value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(this)
            }

            if (gatt.device.name == leftDeviceName) leftWriteChar = writeChar else rightWriteChar = writeChar

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
                    if (gatt.device.name == leftDeviceName) {
                        json.optJSONArray("fsr_left")?.let {
                            for (i in 0 until it.length()) fsrLeft[i] = it.getInt(i)
                        }
                        yawLeft = json.optDouble("yaw_angle", 0.0)
                        squatPostureLeft = json.optString("squat_posture", "")
                    } else {
                        json.optJSONArray("fsr_right")?.let {
                            for (i in 0 until it.length()) fsrRight[i] = it.getInt(i)
                        }
                        yawRight = json.optDouble("yaw_angle", 0.0)
                        squatPostureRight = json.optString("squat_posture", "")
                    }
                } catch (e: Exception) {
                    Log.e("BLE", "JSON 오류: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun stopMeasurement() {
        isMeasuring = false

        leftWriteChar?.apply {
            value = "stop".toByteArray()
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            leftGatt?.writeCharacteristic(this)
        }
        rightWriteChar?.apply {
            value = "stop".toByteArray()
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            rightGatt?.writeCharacteristic(this)
        }

        leftGatt?.disconnect(); rightGatt?.disconnect()
        leftGatt?.close(); rightGatt?.close()
        isLeftConnected = false; isRightConnected = false

        for (i in 0 until fsrLeft.size) fsrLeft[i] = 0
        for (i in 0 until fsrRight.size) fsrRight[i] = 0
        yawLeft = 0.0
        yawRight = 0.0
        squatPostureLeft = ""
        squatPostureRight = ""
    }
}

@Composable
fun FootOverlayWithRotation(
    fsrValues: List<Int>,
    yaw: Double,
    squatPosture: String,
    isLeft: Boolean
) {
    val baseImage = if (isLeft) R.drawable.foot_l else R.drawable.foot_r
    val overlayImages = if (isLeft)
        listOf(
            R.drawable.foot_angle_l_fsr4, R.drawable.foot_angle_l_fsr5,
            R.drawable.foot_angle_l_fsr6, R.drawable.foot_angle_l_fsr7, R.drawable.foot_angle_l_fsr15
        )
    else
        listOf(
            R.drawable.foot_angle_r_fsr4, R.drawable.foot_angle_r_fsr5,
            R.drawable.foot_angle_r_fsr6, R.drawable.foot_angle_r_fsr7, R.drawable.foot_angle_r_fsr15
        )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer(rotationZ = yaw.toFloat()),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = baseImage),
            contentDescription = if (isLeft) "왼발" else "오른발",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        fsrValues.forEachIndexed { index, value ->
            val alpha = (value.coerceIn(0, 5000).toFloat() / 5000f)
            val tint = if (value < 50) Color.Gray
            else if (isLeft) Color.Red.copy(alpha = alpha)
            else Color.Blue.copy(alpha = alpha)

            Image(
                painter = painterResource(id = overlayImages[index]),
                contentDescription = "FSR $index",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                colorFilter = ColorFilter.tint(tint)
            )
        }
    }
}
