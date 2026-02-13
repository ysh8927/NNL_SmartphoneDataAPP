package com.example.nll_sensortotext

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanResult as BLEScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class ArduinoLoggingActivity : AppCompatActivity() {

    private lateinit var bleLog: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    // CSV 로거
    private var csvWriter: BufferedWriter? = null
    private var sensorCsvFileName: String = ""
    private var sessionStartTimestamp: Long = 0L

    // Wi-Fi 로깅 메모리
    private val wifiScanResultsByTime = mutableListOf<Pair<Long, Map<String, Triple<String, Int, Int>>>>()
    private val allBssidSet = mutableSetOf<String>()

    private lateinit var wifiManager: WifiManager
    private val wifiHandler = Handler(Looper.getMainLooper())
    private val wifiScanIntervalMs = 4000L

    // BLE 디바이스 정보
    private val DEVICE_NAME = "Nano33BLE-Logger"
    private val SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
    private val CHARACTERISTIC_WRITE_UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")
    private val CHARACTERISTIC_NOTIFY_UUID = UUID.fromString("00002A1C-0000-1000-8000-00805f9b34fb")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_arduino_logging)

        bleLog     = findViewById(R.id.tvBLELog)
        btnConnect = findViewById(R.id.btnConnectBLE)
        btnStart   = findViewById(R.id.btnStartLogging)
        btnStop    = findViewById(R.id.btnStopLogging)

        bluetoothAdapter =
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        btnConnect.setOnClickListener { startBLEScan() }
        btnStart.setOnClickListener   { sendStartCommand() }
        btnStop.setOnClickListener    { sendStopCommand() }

        checkPermissions()
    }

    private fun checkPermissions() {
        val toRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                toRequest += Manifest.permission.BLUETOOTH_SCAN
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                toRequest += Manifest.permission.BLUETOOTH_CONNECT
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                toRequest += Manifest.permission.ACCESS_FINE_LOCATION
            }
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            toRequest += Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this,
                toRequest.toTypedArray(), 100)
        } else {
            logText("모든 권한이 허용되었습니다.")
        }
    }

    private fun startBLEScan() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED) {
            logText("❗ BLE 스캔 권한이 없습니다.")
            return
        }
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val filter   = ScanFilter.Builder().setDeviceName(DEVICE_NAME).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, bleScanCallback)
        logText("BLE 스캔 시작...")
        Handler(Looper.getMainLooper())
            .postDelayed({ scanner.stopScan(bleScanCallback) }, 10000)
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: BLEScanResult) {
            if (result.device.name == DEVICE_NAME) {
                logText("BLE 장치 발견: ${result.device.name}")
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                connectToDevice(result.device)
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            logText("❗ BLE 연결 권한이 없습니다.")
            return
        }
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
        logText("BLE 연결 중...")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt?, status: Int, newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                logText("BLE 연결 완료!")
                gatt?.requestMtu(100)
                gatt?.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            val service    = gatt?.getService(SERVICE_UUID)
            writeCharacteristic = service
                ?.getCharacteristic(CHARACTERISTIC_WRITE_UUID)
            val notifyChar = service
                ?.getCharacteristic(CHARACTERISTIC_NOTIFY_UUID)

            if (notifyChar != null &&
                ActivityCompat.checkSelfPermission(
                    this@ArduinoLoggingActivity,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED) {
                gatt.setCharacteristicNotification(notifyChar, true)
                val descriptor = notifyChar.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                )
                descriptor?.value =
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
                logText("Notify 설정 완료!")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?
        ) {
            val rawBytes = characteristic?.value ?: return
            // 0x00 바이너리를 잘라내고 문자열로 변환
            val len = rawBytes.indexOfFirst { it == 0.toByte() }
                .let { if (it < 0) rawBytes.size else it }
            val sensorData = String(rawBytes, 0, len, Charsets.UTF_8)

            val timestamp = System.currentTimeMillis()
            val row = "$timestamp,$sensorData\n"
            try {
                csvWriter?.write(row)
                csvWriter?.flush()
            } catch (e: Exception) {
                e.printStackTrace()
                logText("CSV 센서 쓰기 에러: ${e.message}")
            }
            logText("수신: $sensorData")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logText("✅ 명령 전송 성공")
            } else {
                logText("❌ 명령 전송 실패: status=$status")
            }
        }
    }

    private fun sendStartCommand() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            logText("BLUETOOTH_CONNECT 권한 필요")
            return
        }
        writeCharacteristic?.let {
            it.value = "start".toByteArray()
            try {
                bluetoothGatt?.writeCharacteristic(it)
                logText("\"start\" 명령 전송 완료")
                startCsvLogging()
            } catch (e: SecurityException) {
                logText("보안 예외: ${e.message}")
            }
        } ?: logText("Write 캐릭터리스틱 없음")
    }

    private fun sendStopCommand() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            logText("BLUETOOTH_CONNECT 권한 필요")
            return
        }
        writeCharacteristic?.let {
            it.value = "stop".toByteArray()
            try {
                bluetoothGatt?.writeCharacteristic(it)
                logText("\"stop\" 명령 전송 완료")
                stopCsvLogging()
            } catch (e: SecurityException) {
                logText("보안 예외: ${e.message}")
            }
        } ?: logText("Write 캐릭터리스틱 없음")
    }

    private fun startCsvLogging() {
        sessionStartTimestamp = System.currentTimeMillis()
        val downloadsDir = Environment
            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        // 센서 CSV 파일 생성
        sensorCsvFileName = "sensordata_${sessionStartTimestamp}.csv"
        File(downloadsDir, sensorCsvFileName).apply {
            csvWriter = BufferedWriter(FileWriter(this))
            csvWriter?.write("timestamp,clock,ax,ay,az,gx,gy,gz\n")
            csvWriter?.flush()
        }

        // Wi-Fi 메모리 초기화 및 리시버 등록
        wifiScanResultsByTime.clear()
        allBssidSet.clear()
        registerReceiver(
            wifiScanReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )
        wifiHandler.post(wifiRunnable)

        logText("CSV 로깅 시작: $sensorCsvFileName")
    }

    private fun stopCsvLogging() {
        try {
            csvWriter?.close()
            csvWriter = null
            logText("CSV 로깅 종료")
        } catch (e: Exception) {
            e.printStackTrace()
            logText("CSV 종료 에러: ${e.message}")
        }

        wifiHandler.removeCallbacks(wifiRunnable)
        try { unregisterReceiver(wifiScanReceiver) } catch (_: IllegalArgumentException) {}

        saveWifiCsv()
    }

    private val wifiRunnable = object : Runnable {
        override fun run() {
            if (ActivityCompat.checkSelfPermission(
                    this@ArduinoLoggingActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED) {
                logText("❗ WiFi 스캔 권한이 없습니다.")
                return
            }
            wifiManager.startScan()
            wifiHandler.postDelayed(this, wifiScanIntervalMs)
        }
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val success = intent
                ?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
            if (!success) return

            val results = wifiManager.scanResults
            val timestamp = System.currentTimeMillis()
            val map = mutableMapOf<String, Triple<String, Int, Int>>()
            results.forEach { res ->
                map[res.BSSID] = Triple(res.SSID, res.level, res.frequency)
                allBssidSet.add(res.BSSID)
            }
            wifiScanResultsByTime.add(timestamp to map)
            logText("Wi-Fi 스캔: ${results.size}개 AP")
        }
    }

    private fun saveWifiCsv() {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val ts = sdf.format(Date(sessionStartTimestamp))
        val downloadsDir = Environment
            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val wifiFileName = "WifiData_$ts.csv"

        File(downloadsDir, wifiFileName).apply {
            BufferedWriter(FileWriter(this)).use { writer ->
                // 헤더: Timestamp,"MAC,band,SSID",...
                val delimiter = ","
                val bssidList = allBssidSet.sorted()
                val headerParts = mutableListOf("Timestamp")
                bssidList.forEach { bssid ->
                    val tri = wifiScanResultsByTime
                        .mapNotNull { it.second[bssid] }.firstOrNull()
                    val band = tri?.third?.let { if (it >= 5000) "5.0" else "2.4" } ?: ""
                    val ssid = tri?.first ?: ""
                    headerParts.add("\"$bssid,$band,$ssid\"")
                }
                writer.write(headerParts.joinToString(delimiter) + "\n")

                // 데이터 행: Timestamp, RSSI...
                for ((time, map) in wifiScanResultsByTime) {
                    val parts = mutableListOf(time.toString())
                    bssidList.forEach { bssid ->
                        parts.add(map[bssid]?.second?.toString() ?: "-100")
                    }
                    writer.write(parts.joinToString(delimiter) + "\n")
                }
            }
        }
        logText("Wi-Fi CSV 저장 완료: $wifiFileName")
        Toast.makeText(this, "Wi-Fi 데이터 저장: $wifiFileName", Toast.LENGTH_LONG).show()
    }

    private fun logText(text: String) {
        runOnUiThread {
            bleLog.append("▶ $text\n")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt?.close()
        }
        try { unregisterReceiver(wifiScanReceiver) } catch (_: IllegalArgumentException) {}
    }
}
