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
import android.util.TypedValue
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private lateinit var statusText: TextView
    private lateinit var listLabel: TextView
    private lateinit var listView: ListView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var deviceListAdapter: ArrayAdapter<String>
    private val discoveredDevices = mutableListOf<BluetoothDevice>()

    private lateinit var hexInputField: EditText
    private lateinit var asciiInputField: EditText
    private lateinit var crlfCheckBox: CheckBox

    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // =========================================================================
    // [Telink SPP 전용 UUID 3종 세팅]
    // =========================================================================
    private val TELINK_SPP_SERVICE_UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1910")
    private val TELINK_SPP_NOTIFY_UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d2b10")
    private val TELINK_SPP_WRITE_UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d2b11")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        checkPermissions()
    }

    // 스마트폰 화면 밀도(dp)를 픽셀(px)로 변환하는 함수 (어떤 폰에서든 동일한 줄 수 보장)
    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    // 리스트 창 높이를 동적으로 변경 (확장 시 약 6~7줄 = 290dp, 축소 시 1줄 = 52dp)
    private fun setListExpanded(expanded: Boolean) {
        handler.post {
            val targetHeight = if (expanded) dpToPx(290) else dpToPx(52)
            val params = listView.layoutParams
            params.height = targetHeight
            listView.layoutParams = params
            listView.requestLayout() // 리스트가 줄어들면 아래 로그 창이 자동으로 커짐
        }
    }

    private fun setupUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
        }

        statusText = TextView(this).apply {
            text = "상태: 대기 중 (권한을 허용해 주세요)"
            textSize = 15f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dpToPx(8))
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

        // 검색된 기기 목록 라벨
        listLabel = TextView(this).apply {
            text = "▼ 검색된 기기 (스캔 시 최소 6개 표시 / 터치 시 1줄 축소):"
            setPadding(0, dpToPx(8), 0, dpToPx(4))
        }
        rootLayout.addView(listLabel)

        // 기기 리스트뷰 (초기에는 6개 이상 보이도록 290dp로 설정)
        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listView = ListView(this).apply {
            adapter = deviceListAdapter
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(290))
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setOnItemClickListener { _, _, position, _ ->
                if (position < discoveredDevices.size) {
                    val selectedDevice = discoveredDevices[position]
                    val selectedText = deviceListAdapter.getItem(position) ?: "${selectedDevice.name ?: "Unknown"} [${selectedDevice.address}]"

                    // 1. 선택한 기기 1개만 남기고 나머지 목록 정리
                    discoveredDevices.clear()
                    discoveredDevices.add(selectedDevice)
                    deviceListAdapter.clear()
                    deviceListAdapter.add("★ 연결 대상: $selectedText")

                    // 2. 리스트 창을 1줄 높이로 즉시 축소 (로그 창이 자동으로 넓어짐)
                    listLabel.text = "▼ 현재 선택된 기기 (다시 찾으려면 'BLE 스캔' 터치):"
                    setListExpanded(false)

                    // 3. 연결 시작
                    connectToDevice(selectedDevice)
                }
            }
        }
        rootLayout.addView(listView)

        // 1. Hex 입력창 + 단독 전송 버튼
        val hexRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(8), 0, 0)
        }
        hexInputField = EditText(this).apply {
            hint = "Hex 입력 (예: 01 FF A0)"
            setSingleLine(true)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sendHexBtn = Button(this).apply {
            text = "Hex 전송"
            setOnClickListener { sendCombinedData(sendHex = true, sendAscii = false) }
        }
        hexRow.addView(hexInputField)
        hexRow.addView(sendHexBtn)
        rootLayout.addView(hexRow)

        // 2. ASCII 입력창 + 단독 전송 버튼
        val asciiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(2), 0, 0)
        }
        asciiInputField = EditText(this).apply {
            hint = "ASCII 입력 (예: AT+VER)"
            setSingleLine(true)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sendAsciiBtn = Button(this).apply {
            text = "ASCII 전송"
            setOnClickListener { sendCombinedData(sendHex = false, sendAscii = true) }
        }
        asciiRow.addView(asciiInputField)
        asciiRow.addView(sendAsciiBtn)
        rootLayout.addView(asciiRow)

        // 줄바꿈 옵션 및 [Hex + ASCII 동시 전송] 버튼을 한 줄에 배치해 공간 절약
        val optionAndBothRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(2), 0, dpToPx(4))
        }
        crlfCheckBox = CheckBox(this).apply {
            text = "\\r\\n 추가"
            isChecked = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val sendBothBtn = Button(this).apply {
            text = "★ Hex + ASCII 합쳐서 전송"
            setBackgroundColor(Color.parseColor("#D0E8FF"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dpToPx(8)
            }
            setOnClickListener { sendCombinedData(sendHex = true, sendAscii = true) }
        }
        optionAndBothRow.addView(crlfCheckBox)
        optionAndBothRow.addView(sendBothBtn)
        rootLayout.addView(optionAndBothRow)

        // 실시간 통신 로그 영역 (weight = 1f 로 설정되어 리스트가 줄어들면 자동으로 길어짐)
        val logLabel = TextView(this).apply {
            text = "▼ 상세 연결 및 실시간 통신 로그:"
            setPadding(0, dpToPx(4), 0, dpToPx(4))
        }
        rootLayout.addView(logLabel)

        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }
        logText = TextView(this).apply {
            text = "[시스템 준비 완료]\n" +
                   "• Target Service: $TELINK_SPP_SERVICE_UUID\n" +
                   "• Notify UUID: $TELINK_SPP_NOTIFY_UUID\n" +
                   "• Write UUID: $TELINK_SPP_WRITE_UUID\n" +
                   "-----------------------------------------\n"
            setTextColor(Color.parseColor("#00FF00"))
            textSize = 12.5f
            setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
        }
        logScroll.addView(logText)
        rootLayout.addView(logScroll)

        setContentView(rootLayout)
    }

    // 타임스탬프가 포함된 상세 로그 출력 함수
    private fun appendLog(msg: String) {
        val timeStr = timeFormat.format(Date())
        handler.post {
            logText.append("[$timeStr] $msg\n")
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
            when {
                c in 32..126 -> sb.append(c.toChar())
                c == 10 -> sb.append("\\n")
                c == 13 -> sb.append("\\r")
                else -> sb.append(".")
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
            appendLog("[권한 요청] BLE 스캔 및 연결 권한을 요청합니다.")
            requestPermissions(missing.toTypedArray(), 100)
        } else {
            updateStatus("준비 완료 ('BLE 스캔'을 누르세요)")
            appendLog("[권한 확인] 모든 블루투스 및 위치 권한이 정상입니다.")
        }
    }

    private fun startBleScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            appendLog("[오류] 블루투스가 꺼져있거나 기기에서 BLE를 지원하지 않습니다.")
            return
        }
        if (isScanning) {
            scanner.stopScan(scanCallback)
            isScanning = false
        }

        // 스캔 시작 시 리스트 창을 다시 6개 이상 보이도록 확장
        listLabel.text = "▼ 검색된 기기 (터치하면 연결되며 1줄로 축소됩니다):"
        setListExpanded(true)

        discoveredDevices.clear()
        deviceListAdapter.clear()
        isScanning = true
        updateStatus("주변 BLE 기기 스캔 중... (5초)")
        appendLog("=========================================")
        appendLog("[스캔 시작] 주변 BLE 기기를 탐색합니다 (5초간 진행)...")

        scanner.startScan(scanCallback)

        handler.postDelayed({
            if (isScanning) {
                scanner.stopScan(scanCallback)
                isScanning = false
                updateStatus("스캔 완료 (총 ${discoveredDevices.size}개 기기 발견)")
                appendLog("[스캔 종료] 총 ${discoveredDevices.size}개의 기기가 리스트에 등록되었습니다.")
            }
        }, 5000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val rssi = result.rssi
            if (!discoveredDevices.any { it.address == device.address }) {
                discoveredDevices.add(device)
                val name = device.name ?: "이름 없는 기기"
                handler.post {
                    // 한 줄에 깔끔하게 들어가도록 표시 (최소 6개 이상 리스트에 표시됨)
                    deviceListAdapter.add("$name [${device.address}] (${rssi}dBm)")
                }
                appendLog("  • 발견: $name (${device.address}) | 신호세기: ${rssi}dBm")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            appendLog("[스캔 실패] 스캔 오류 발생! 에러 코드: $errorCode")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (isScanning) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
            appendLog("[스캔 중지] 연결을 위해 진행 중인 스캔을 중지했습니다.")
        }
        disconnectGatt(silent = true)

        val devName = device.name ?: "이름 없음"
        updateStatus("연결 진행 중: $devName (${device.address})")
        appendLog("=========================================")
        appendLog("[연결 1단계] GATT 서버 연결 요청 시작")
        appendLog("  └ 대상 기기명: $devName")
        appendLog("  └ 대상 MAC 주소: ${device.address}")
        appendLog("  └ connectGatt(autoConnect = false) 호출 중...")

        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private fun disconnectGatt(silent: Boolean = false) {
        bluetoothGatt?.let {
            if (!silent) appendLog("[연결 해제 요청] 기기와의 GATT 연결을 종료하고 리소스를 닫습니다.")
            it.disconnect()
            it.close()
            bluetoothGatt = null
            writeCharacteristic = null
            if (!silent) updateStatus("연결 해제됨 ('BLE 스캔'을 눌러 다시 검색하세요)")
        }
    }

    // 특성(Characteristic)이 지원하는 속성들을 읽기 쉬운 문자열로 변환
    private fun getPropertiesString(props: Int): String {
        val list = mutableListOf<String>()
        if ((props and BluetoothGattCharacteristic.PROPERTY_READ) != 0) list.add("READ")
        if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) list.add("WRITE")
        if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) list.add("WRITE_NO_RSP")
        if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) list.add("NOTIFY")
        if ((props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) list.add("INDICATE")
        return if (list.isEmpty()) "NONE(0x${String.format("%02X", props)})" else list.joinToString(" | ")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val statusMsg = if (status == BluetoothGatt.GATT_SUCCESS) "GATT_SUCCESS(0)" else "오류코드($status)"
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    updateStatus("물리적 연결 성공! MTU 확장 요청 중...")
                    appendLog("[연결 2단계] 물리적 연결(STATE_CONNECTED) 완료! (상태: $statusMsg)")
                    appendLog("[연결 3단계] 고속 데이터 통신을 위해 MTU 512바이트 확장 요청 중...")
                    val mtuReq = gatt.requestMtu(512)
                    if (!mtuReq) {
                        appendLog("  └ [알림] MTU 요청 미지원 -> 즉시 서비스 탐색(discoverServices)으로 이동")
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    updateStatus("연결 끊김 (상태 코드: $status)")
                    appendLog("[연결 끊김] 기기와 연결이 해제되었습니다. (STATE_DISCONNECTED, 상태: $statusMsg)")
                    if (status == 133) {
                        appendLog("  └ [팁] 에러 133은 일시적인 블루투스 지연이거나 기기가 멀리 있을 때 발생합니다. 다시 터치해 재연결해보세요.")
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                appendLog("  └ [MTU 설정 성공] 최대 전송 패킷 크기(MTU)가 ${mtu}바이트로 설정되었습니다.")
            } else {
                appendLog("  └ [MTU 설정 알림] 기본 MTU(${mtu}바이트)로 유지됩니다. (코드: $status)")
            }
            appendLog("[연결 4단계] 기기 내부 GATT 서비스 및 특성(UUID) 목록 탐색 시작(discoverServices)...")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appendLog("[오류] 서비스 탐색 실패! 상태 코드: $status")
                return
            }

            val services = gatt.services
            appendLog("  └ 서비스 탐색 완료! 총 ${services.size}개의 Service가 발견되었습니다.")
            for ((idx, srv) in services.withIndex()) {
                val isTelink = srv.uuid == TELINK_SPP_SERVICE_UUID
                val tag = if (isTelink) " ★[Telink SPP 서비스]" else ""
                appendLog("    (${idx + 1}) Service: ${srv.uuid}$tag (특성 ${srv.characteristics.size}개)")
            }

            appendLog("[연결 5단계] Telink 전용 송/수신 채널 매핑 및 검증 시작...")
            val telinkService = gatt.getService(TELINK_SPP_SERVICE_UUID)
            if (telinkService != null) {
                appendLog("  ✔ Telink SPP Service(1910) 확인 완료!")

                // 1. 송신(CLIENT2SERVER: 2b11) 특성 확인
                val writeChar = telinkService.getCharacteristic(TELINK_SPP_WRITE_UUID)
                if (writeChar != null) {
                    writeCharacteristic = writeChar
                    val propStr = getPropertiesString(writeChar.properties)
                    appendLog("  ✔ 송신 특성(2b11) 연결 완료! [지원 속성: $propStr]")
                } else {
                    appendLog("  ✘ [경고] 송신 특성(2b11)을 찾지 못했습니다!")
                }

                // 2. 수신(SERVER2CLIENT: 2b10) 특성 확인 및 CCCD 활성화
                val notifyChar = telinkService.getCharacteristic(TELINK_SPP_NOTIFY_UUID)
                if (notifyChar != null) {
                    val propStr = getPropertiesString(notifyChar.properties)
                    appendLog("  ✔ 수신 특성(2b10) 발견! [지원 속성: $propStr]")

                    val localNotifyOk = gatt.setCharacteristicNotification(notifyChar, true)
                    appendLog("  └ 안드로이드 내부 수신 알림(setCharacteristicNotification) 활성화: $localNotifyOk")

                    val descriptor = notifyChar.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        appendLog("[연결 6단계] 기기에 실시간 수신(Notify) 허용 명령(CCCD 0x2902) 전송 중...")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        val descWriteReq = gatt.writeDescriptor(descriptor)
                        appendLog("  └ CCCD 디스크립터 쓰기 요청 결과: $descWriteReq")
                    } else {
                        appendLog("  └ [알림] CCCD(0x2902) 디스크립터가 없어 즉시 수신 대기 상태로 전환합니다.")
                        updateStatus("Telink SPP 통신 준비 완료!")
                    }
                } else {
                    appendLog("  ✘ [경고] 수신 특성(2b10)을 찾지 못했습니다!")
                }
            } else {
                appendLog("  ✘ [오류] 이 기기에는 Telink SPP 서비스($TELINK_SPP_SERVICE_UUID)가 존재하지 않습니다.")
                updateStatus("연결됨 (Telink 서비스 없음)")
            }
        }

        // 6단계 CCCD 디스크립터 쓰기가 실제로 완료되었는지 확인하는 콜백
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    appendLog("  ✔ [CCCD 설정 완료] 기기에서 실시간 수신(Notify: 2b10)이 최종 활성화되었습니다!")
                    appendLog("★ 모든 연결 및 초기화 과정이 100% 완료되었습니다. 데이터를 전송해 보세요! ★")
                    appendLog("=========================================")
                    updateStatus("통신 준비 완료! (Telink SPP 연결됨)")
                } else {
                    appendLog("  ✘ [CCCD 설정 오류] 디스크립터 쓰기 실패 (상태 코드: $status)")
                    updateStatus("연결됨 (수신 알림 설정 오류)")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            if (data != null) {
                val asciiMsg = bytesToPrintableAscii(data)
                val hexMsg = data.joinToString(" ") { String.format("%02X", it) }
                appendLog("[수신 ◀ 2b10] (${data.size} Bytes)\n   • ASCII : \"$asciiMsg\"\n   • Hex   : [$hexMsg]")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                appendLog("  └ [송신 완료 확인] 기기가 패킷을 정상적으로 수신했습니다 (GATT_SUCCESS).")
            } else {
                appendLog("  └ [송신 실패] 기기 쓰기 응답 오류! 상태 코드: $status")
            }
        }
    }

    private fun sendCombinedData(sendHex: Boolean, sendAscii: Boolean) {
        val char = writeCharacteristic
        val gatt = bluetoothGatt
        if (gatt == null || char == null) {
            appendLog("[오류] 아직 기기와 연결되지 않았거나 송신 특성(2b11)이 준비되지 않았습니다.")
            return
        }

        var finalBytes = ByteArray(0)

        // 1. Hex 입력값 파싱
        if (sendHex) {
            val hexStr = hexInputField.text.toString().trim()
            if (hexStr.isNotEmpty()) {
                try {
                    val cleanHex = hexStr.replace("0x", "", ignoreCase = true)
                        .replace(" ", "")
                        .replace(",", "")
                        .replace("-", "")
                    if (cleanHex.length % 2 != 0) {
                        appendLog("[입력 오류] Hex 데이터는 짝수 글자(2자리씩)로 입력해주세요. (예: 01 FF)")
                        return
                    }
                    val hexBytes = cleanHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    finalBytes += hexBytes
                } catch (e: Exception) {
                    appendLog("[입력 오류] 잘못된 Hex 문자가 포함되어 있습니다. (0~9, A~F만 가능)")
                    return
                }
            }
        }

        // 2. ASCII 입력값 파싱
        if (sendAscii) {
            val asciiStr = asciiInputField.text.toString()
            if (asciiStr.isNotEmpty()) {
                val textToSend = if (crlfCheckBox.isChecked) "$asciiStr\r\n" else asciiStr
                val asciiBytes = textToSend.toByteArray(Charsets.UTF_8)
                finalBytes += asciiBytes
            }
        }

        if (finalBytes.isEmpty()) {
            appendLog("[알림] 전송할 Hex 또는 ASCII 데이터를 입력해주세요.")
            return
        }

        val writeTypeStr: String
        if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            writeTypeStr = "WRITE_NO_RESPONSE"
        } else {
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            writeTypeStr = "WRITE_DEFAULT"
        }

        char.value = finalBytes
        val reqSuccess = gatt.writeCharacteristic(char)

        val asciiLog = bytesToPrintableAscii(finalBytes)
        val hexLog = finalBytes.joinToString(" ") { String.format("%02X", it) }

        appendLog("[송신 ▶ 2b11] (${finalBytes.size} Bytes | 모드: $writeTypeStr) -> 요청: ${if (reqSuccess) "성공" else "실패"}\n   • ASCII : \"$asciiLog\"\n   • Hex   : [$hexLog]")
    }
}
