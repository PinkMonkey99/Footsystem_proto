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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class FootActivity : ComponentActivity() {
    private var bluetoothGatt: BluetoothGatt? = null
    private val espDeviceName = "ESP32-S3 BLE Scale"
    private val serviceUUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
    private val notifyCharUUID = UUID.fromString("abcdef01-1234-5678-1234-56789abcdef0")
    private val writeCharUUID = UUID.fromString("abcdef02-1234-5678-1234-56789abcdef0")

    private lateinit var leftColorState: MutableState<String>
    private lateinit var isConnectedState: MutableState<Boolean>
    private lateinit var connectedDeviceNameState: MutableState<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissionsLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.values.all { it }) {
                startBleScan()
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
        } else {
            startBleScan()
        }

        setContent {
            MyApplicationTheme {
                val leftColor = remember { mutableStateOf("RED") }
                val rightColor = remember { mutableStateOf("BLUE") }
                val isConnected = remember { mutableStateOf(false) }
                val connectedDeviceName = remember { mutableStateOf("") }
                leftColorState = leftColor
                isConnectedState = isConnected
                connectedDeviceNameState = connectedDeviceName

                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        IconButton(
                            onClick = { finish() },
                            modifier = Modifier.padding(start = 16.dp, top = 48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "뒤로 가기"
                            )
                        }
                    }

                    Text(
                        text = if (isConnected.value)
                            "✅ 연결됨: ${connectedDeviceName.value}"
                        else
                            "🔄 BLE 연결 대기 중...",
                        color = if (isConnected.value) Color.Green else Color.Gray,
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .align(Alignment.CenterHorizontally)
                    )

                    Text("왼발 센서 색상", modifier = Modifier.padding(top = 32.dp, bottom = 4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(onClick = { leftColor.value = "RED" }) { Text("RED") }
                        Button(onClick = { leftColor.value = "GREEN" }) { Text("GREEN") }
                        Button(onClick = { leftColor.value = "BLUE" }) { Text("BLUE") }
                    }

                    FootPressureScreen(leftColor = leftColor.value, rightColor = rightColor.value)
                }
            }
        }
    }

    private fun startBleScan() {
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
                    bluetoothGatt = result.device.connectGatt(this@FootActivity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                }
            }
        }
        scanner.startScan(scanCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (ActivityCompat.checkSelfPermission(this@FootActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
                isConnectedState.value = true
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(serviceUUID)
            val writeChar = service.getCharacteristic(writeCharUUID)
            val notifyChar = service.getCharacteristic(notifyCharUUID)

            if (ActivityCompat.checkSelfPermission(this@FootActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }

            writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            writeChar.value = "measure".toByteArray()
            gatt.writeCharacteristic(writeChar)

            gatt.setCharacteristicNotification(notifyChar, true)
            val descriptor = notifyChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            gatt.writeDescriptor(descriptor)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == notifyCharUUID) {
                val weight = ByteBuffer.wrap(characteristic.value)
                    .order(ByteOrder.LITTLE_ENDIAN).float

                println("📥 수신된 무게: $weight g")

                leftColorState.value = if (weight > 5f) "GREEN" else "RED"
            }
        }
    }
}

@Composable
fun FootPressureScreen(leftColor: String, rightColor: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.foot3_l),
                contentDescription = "Left Foot",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(550.dp).offset(y = 45.dp)
            )
            Box(
                modifier = Modifier
                    .offset(x = 50.dp, y = 65.dp)
                    .size(30.dp)
                    .background(
                        color = when (leftColor) {
                            "GREEN" -> Color.Green
                            "BLUE" -> Color.Blue
                            else -> Color.Red
                        },
                        shape = CircleShape
                    )
            )
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.foot3_r),
                contentDescription = "Right Foot",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(550.dp).offset(y = -45.dp)
            )
            Box(
                modifier = Modifier
                    .offset(x = 50.dp, y = -60.dp)
                    .size(30.dp)
                    .background(
                        color = when (rightColor) {
                            "GREEN" -> Color.Green
                            "BLUE" -> Color.Blue
                            else -> Color.Red
                        },
                        shape = CircleShape
                    )
            )
        }
    }
}
