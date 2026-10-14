package com.example.bleapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import java.util.*

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var deviceListAdapter: ArrayAdapter<String>
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private lateinit var inputField: EditText
    private lateinit var hexCheckBox: CheckBox

    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    // 표준 CCCD UUID (실시간 데이터 수신 알림 활성화용)
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        checkPermissions()
    }

    private fun setupUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        statusText = TextView(this).apply {
            text = "상태: 대기 중 (권한을 허용해 주세요)"
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(statusText)

        // 상단 컨트롤 버튼 (스캔 / 연결 해제 / 로그 지우기)
        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val scanBtn = Button(this).apply {
            text = "BLE 스캔 시작"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { startBleScan() }
        }
        val disconnectBtn = Button(this).apply {
            text = "연결 해제"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { disconnectGatt() }
        }
        val clearBtn = Button(this).apply {
            text = "로그 지우기"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { logText.text = "" }
        }
        btnLayout.addView(scanBtn)
        btnLayout.addView(disconnectBtn)
        btnLayout.addView(clearBtn)
        rootLayout.addView(btnLayout)

        // 검색된 기기 목록
        val listLabel = TextView(this).apply {
            text = "▼ 검색된 기기 (터치하여 연결):"
            setPadding(0, 16, 0, 8)
        }
        rootLayout.addView(listLabel)

        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        val listView = ListView(this).apply {
            adapter = deviceListAdapter
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 380)
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setOnItemClickListener { _, _, position, _ ->
                val device = discoveredDevices[position]
                connectToDevice(device)
            }
        }
        rootLayout.addView(listView)

        // 데이터 전송 영역
        val sendOptionLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 0)
        }
        hexCheckBox = CheckBox(this).apply {
            text = "Hex 바이트로 전송 (예: 01 FF A0)"
        }
        sendOptionLayout.addView(hexCheckBox)
        rootLayout.addView(sendOptionLayout)

        val sendLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 16)
        }
        inputField = EditText(this).apply {
            hint = "보낼 데이터 입력"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sendBtn = Button(this).apply {
            text = "전송(Write)"
            setOnClickListener { sendData(inputField.text.toString()) }
        }
        sendLayout.addView(inputField)
        sendLayout.addView(sendBtn)
        rootLayout.addView(sendLayout)

        // 실시간 통신 로그 영역
        val logLabel = TextView(this).apply {
            text = "▼ 실시간 통신 로그 (수신/송신 내역):"
            setPadding(0, 8, 0, 8)
        }
        rootLayout.addView(logLabel)

        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }
        logText = TextView(this).apply {
            text = "앱이 시작되었습니다.\n"
            setTextColor(Color.parseColor("#00FF00"))
            textSize = 13f
            setPadding(20, 20, 20, 20)
        }
        logScroll.addView(logText)
        rootLayout.addView(logScroll)

        setContentView(rootLayout)
    }

    private fun appendLog(msg: String) {
        handler.post {
            logText.append("$msg\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun updateStatus(msg: String) {
        handler.post { statusText.text = "상태: $msg" }
    }

    private fun checkPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        } else {
            updateStatus("준비 완료 (BLE 스캔 시작을 누르세요)")
        }
    }

    private fun startBleScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            appendLog("[오류] 스마트폰 블루투스가 꺼져있거나 지원하지 않습니다.")
            return
        }
        if (isScanning) return

        discoveredDevices.clear()
        deviceListAdapter.clear()
        isScanning = true
        updateStatus("주변 BLE 기기 검색 중... (5초간 진행)")
        appendLog("--- BLE 스캔 시작 ---")

        scanner.startScan(scanCallback)

        handler.postDelayed({
            if (isScanning) {
                scanner.stopScan(scanCallback)
                isScanning = false
                updateStatus("스캔 완료 (${discoveredDevices.size}개 발견)")
                appendLog("--- 스캔 완료 ---")
            }
        }, 5000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (!discoveredDevices.any { it.address == device.address }) {
                discoveredDevices.add(device)
                val name = device.name ?: "이름 없는 기기"
                handler.post {
                    deviceListAdapter.add("$name\n(${device.address})")
                }
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (isScanning) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
        }
        disconnectGatt()
        updateStatus("연결 시도 중: ${device.name ?: device.address}")
        appendLog("기기 연결 시도: ${device.address}")
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private fun disconnectGatt() {
        bluetoothGatt?.let {
            it.disconnect()
            it.close()
            bluetoothGatt = null
            writeCharacteristic = null
            updateStatus("연결 해제됨")
            appendLog("기기 연결이 해제되었습니다.")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                updateStatus("연결 성공! 서비스 탐색 중...")
                appendLog("GATT 서버 연결됨. 내부 서비스 검색 시작...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                updateStatus("연결 끊김")
                appendLog("기기와 연결이 끊어졌습니다.")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                appendLog("--- 발견된 서비스 및 특성 ---")
                var foundWrite = false
                for (service in gatt.services) {
                    appendLog("[Service] ${service.uuid}")
                    for (char in service.characteristics) {
                        val props = char.properties
                        // 데이터 쓰기(Write) 가능한 특성 자동 연결
                        if ((props and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                            writeCharacteristic = char
                            foundWrite = true
                            appendLog("  └ [송신(Write) 연결됨] ${char.uuid}")
                        }
                        // 데이터 수신(Notify/Indicate) 가능한 특성 자동 구독
                        if ((props and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                            gatt.setCharacteristicNotification(char, true)
                            val descriptor = char.getDescriptor(CCCD_UUID)
                            if (descriptor != null) {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                            }
                            appendLog("  └ [수신(Notify) 활성화] ${char.uuid}")
                        }
                    }
                }
                if (foundWrite) {
                    updateStatus("통신 준비 완료! 데이터를 보내보세요.")
                } else {
                    updateStatus("연결됨 (쓰기 가능한 특성을 찾지 못함)")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            if (data != null) {
                val strMsg = String(data, Charsets.UTF_8)
                val hexMsg = data.joinToString(" ") { String.format("%02X", it) }
                appendLog("[수신] 텍스트: $strMsg | Hex: $hexMsg")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                appendLog("[송신 성공] 데이터가 전달되었습니다.")
            } else {
                appendLog("[송신 실패] 오류 코드: $status")
            }
        }
    }

    private fun sendData(input: String) {
        val char = writeCharacteristic
        val gatt = bluetoothGatt
        if (gatt == null || char == null) {
            appendLog("[오류] 먼저 기기에 연결하거나 쓰기 가능한 특성이 있어야 합니다.")
            return
        }
        if (input.isEmpty()) return

        val bytesToSend: ByteArray = try {
            if (hexCheckBox.isChecked) {
                // 공백이나 하이픈 제거 후 Hex 바이트 배열로 변환
                val cleanHex = input.replace(" ", "").replace("-", "")
                cleanHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } else {
                input.toByteArray(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            appendLog("[오류] 잘못된 Hex 형식입니다. (예: 01 FF A0 형태로 입력)")
            return
        }

        char.value = bytesToSend
        val success = gatt.writeCharacteristic(char)
        val hexLog = bytesToSend.joinToString(" ") { String.format("%02X", it) }
        appendLog("[송신 요청] $input (Hex: $hexLog) -> 요청결과: $success")
        inputField.setText("")
    }
}
