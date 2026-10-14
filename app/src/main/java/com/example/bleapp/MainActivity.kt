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

    private lateinit var hexInputField: EditText
    private lateinit var asciiInputField: EditText
    private lateinit var crlfCheckBox: CheckBox

    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    // =========================================================================
    // [Telink SPP 전용 UUID 3종 세팅] (리틀 엔디안 배열 -> 표준 UUID 변환 완료)
    // =========================================================================
    // 1. TELINK_SPP_UUID_SERVICE (0x10, 0x19...)
    private val TELINK_SPP_SERVICE_UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1910")

    // 2. TELINK_SPP_DATA_SERVER2CLIENT (0x10, 0x2B...) -> 수신(Notify) 전용
    private val TELINK_SPP_NOTIFY_UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d2b10")

    // 3. TELINK_SPP_DATA_CLIENT2SERVER (0x11, 0x2B...) -> 송신(Write) 전용
    private val TELINK_SPP_WRITE_UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d2b11")

    // 표준 CCCD UUID (실시간 수신 알림 활성화 디스크립터)
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
            setPadding(28, 28, 28, 28)
        }

        statusText = TextView(this).apply {
            text = "상태: 대기 중 (권한을 허용해 주세요)"
            textSize = 15f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 12)
        }
        rootLayout.addView(statusText)

        // 상단 컨트롤 버튼 (스캔 / 연결 해제 / 로그 지우기)
        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val scanBtn = Button(this).apply {
            text = "BLE 스캔"
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
            setPadding(0, 12, 0, 4)
        }
        rootLayout.addView(listLabel)

        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        val listView = ListView(this).apply {
            adapter = deviceListAdapter
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 280)
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setOnItemClickListener { _, _, position, _ ->
                val device = discoveredDevices[position]
                connectToDevice(device)
            }
        }
        rootLayout.addView(listView)

        // 1. Hex 입력창 + 단독 전송 버튼
        val hexRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        hexInputField = EditText(this).apply {
            hint = "Hex 입력 (예: 01 FF A0)"
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sendHexBtn = Button(this).apply {
            text = "Hex만 전송"
            setOnClickListener { sendCombinedData(sendHex = true, sendAscii = false) }
        }
        hexRow.addView(hexInputField)
        hexRow.addView(sendHexBtn)
        rootLayout.addView(hexRow)

        // 2. ASCII 입력창 + 단독 전송 버튼
        val asciiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 0)
        }
        asciiInputField = EditText(this).apply {
            hint = "ASCII 문자 입력 (예: AT+VER)"
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sendAsciiBtn = Button(this).apply {
            text = "ASCII만 전송"
            setOnClickListener { sendCombinedData(sendHex = false, sendAscii = true) }
        }
        asciiRow.addView(asciiInputField)
        asciiRow.addView(sendAsciiBtn)
        rootLayout.addView(asciiRow)

        // 줄바꿈 옵션 및 [Hex + ASCII 동시 전송] 버튼
        crlfCheckBox = CheckBox(this).apply {
            text = "ASCII 끝에 줄바꿈(\\r\\n) 추가"
            isChecked = false
        }
        rootLayout.addView(crlfCheckBox)

        val sendBothBtn = Button(this).apply {
            text = "★ Hex + ASCII 합쳐서 동시 전송 ★"
            setBackgroundColor(Color.parseColor("#D0E8FF"))
            setOnClickListener { sendCombinedData(sendHex = true, sendAscii = true) }
        }
        rootLayout.addView(sendBothBtn)

        // 실시간 통신 로그 영역
        val logLabel = TextView(this).apply {
            text = "▼ 실시간 통신 로그 (ASCII + Hex 동시 표시):"
            setPadding(0, 8, 0, 4)
        }
        rootLayout.addView(logLabel)

        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }
        logText = TextView(this).apply {
            text = "[Telink SPP 전용 모드 준비 완료]\n" +
                   "- Service: ...1910\n" +
                   "- Notify(수신): ...2b10\n" +
                   "- Write(송신): ...2b11\n\n"
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

    private fun bytesToPrintableAscii(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126) {
                sb.append(c.toChar())
            } else if (c == 10) {
                sb.append("\\n")
            } else if (c == 13) {
                sb.append("\\r")
            } else {
                sb.append(".")
            }
        }
        return sb.toString()
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
            updateStatus("준비 완료 (BLE 스캔을 누르세요)")
        }
    }

    private fun startBleScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            appendLog("[오류] 블루투스가 꺼져있거나 지원하지 않습니다.")
            return
        }
        if (isScanning) return

        discoveredDevices.clear()
        deviceListAdapter.clear()
        isScanning = true
        updateStatus("주변 기기 스캔 중... (5초)")
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
        updateStatus("연결 중: ${device.name ?: device.address}")
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
                updateStatus("연결 성공! MTU 확장 및 서비스 탐색 중...")
                appendLog("GATT 서버 연결됨. 패킷 크기(MTU 512) 확장 요청...")
                // 긴 패킷 송수신을 위해 MTU 확장 요청 후 서비스 탐색
                if (!gatt.requestMtu(512)) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                updateStatus("연결 끊김")
                appendLog("기기와 연결이 끊어졌습니다.")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            appendLog("MTU 크기 설정 완료: $mtu bytes -> 서비스 탐색 시작")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val telinkService = gatt.getService(TELINK_SPP_SERVICE_UUID)
                if (telinkService != null) {
                    appendLog("★ [Telink SPP 서비스(1910) 연결 성공] ★")

                    // 1. 송신(CLIENT2SERVER: 2b11) 특성 찾기
                    val writeChar = telinkService.getCharacteristic(TELINK_SPP_WRITE_UUID)
                    if (writeChar != null) {
                        writeCharacteristic = writeChar
                        appendLog("  └ [송신(Write: 2b11) 준비 완료]")
                    } else {
                        appendLog("  └ [경고] 2b11(Write) 특성을 찾지 못했습니다.")
                    }

                    // 2. 수신(SERVER2CLIENT: 2b10) 특성 찾기 및 Notify 활성화
                    val notifyChar = telinkService.getCharacteristic(TELINK_SPP_NOTIFY_UUID)
                    if (notifyChar != null) {
                        gatt.setCharacteristicNotification(notifyChar, true)
                        val descriptor = notifyChar.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                        appendLog("  └ [수신(Notify: 2b10) 구독 완료]")
                    } else {
                        appendLog("  └ [경고] 2b10(Notify) 특성을 찾지 못했습니다.")
                    }

                    updateStatus("Telink SPP 통신 준비 완료!")
                } else {
                    appendLog("[오류] 기기에 Telink SPP 서비스(1910)가 없습니다.")
                    updateStatus("연결됨 (Telink 서비스 없음)")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            if (data != null) {
                val asciiMsg = bytesToPrintableAscii(data)
                val hexMsg = data.joinToString(" ") { String.format("%02X", it) }
                appendLog("[수신(2b10)] (${data.size}B)\n  ASCII: \"$asciiMsg\"\n  Hex: [$hexMsg]")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appendLog("[송신 오류] 상태 코드: $status")
            }
        }
    }

    private fun sendCombinedData(sendHex: Boolean, sendAscii: Boolean) {
        val char = writeCharacteristic
        val gatt = bluetoothGatt
        if (gatt == null || char == null) {
            appendLog("[오류] 먼저 Telink 기기에 연결해주세요.")
            return
        }

        var finalBytes = ByteArray(0)

        // 1. Hex 입력값 처리
        if (sendHex) {
            val hexStr = hexInputField.text.toString().trim()
            if (hexStr.isNotEmpty()) {
                try {
                    val cleanHex = hexStr.replace("0x", "", ignoreCase = true)
                        .replace(" ", "")
                        .replace(",", "")
                        .replace("-", "")
                    if (cleanHex.length % 2 != 0) {
                        appendLog("[오류] Hex는 짝수 자리(2글자씩)로 입력해주세요. (예: 01 FF)")
                        return
                    }
                    val hexBytes = cleanHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    finalBytes += hexBytes
                } catch (e: Exception) {
                    appendLog("[오류] 잘못된 Hex 값입니다. (0~9, A~F만 가능)")
                    return
                }
            }
        }

        // 2. ASCII 입력값 처리
        if (sendAscii) {
            val asciiStr = asciiInputField.text.toString()
            if (asciiStr.isNotEmpty()) {
                val textToSend = if (crlfCheckBox.isChecked) "$asciiStr\r\n" else asciiStr
                val asciiBytes = textToSend.toByteArray(Charsets.UTF_8)
                finalBytes += asciiBytes
            }
        }

        if (finalBytes.isEmpty()) {
            appendLog("[알림] 전송할 데이터를 입력해주세요.")
            return
        }

        // Telink SPP는 보통 Write No Response(빠른 전송)를 주로 사용하므로 우선 지원 시 설정
        if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        char.value = finalBytes
        val success = gatt.writeCharacteristic(char)

        val asciiLog = bytesToPrintableAscii(finalBytes)
        val hexLog = finalBytes.joinToString(" ") { String.format("%02X", it) }

        appendLog("[송신(2b11)] (${finalBytes.size}B) -> ${if (success) "성공" else "실패"}\n  ASCII: \"$asciiLog\"\n  Hex: [$hexLog]")
    }
}
