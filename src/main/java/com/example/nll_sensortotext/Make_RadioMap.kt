package com.example.nll_sensortotext

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class Make_RadioMap : AppCompatActivity() {

    // 스캔 중 나온 SSID, frequency, RSSI list 를 모두 저장
    data class WifiInfo(
        val ssid: String,
        val frequency: Int,
        val levels: MutableList<Int>
    )
    // 하나의 위치(index)에 대한 평균 RSSI와 SSID/frequency 정보
    data class Measurement(
        val index: String,
        val rssiMap: Map<String, Int>,
        val infoMap: Map<String, Pair<String, Int>>
    )

    private lateinit var wifiManager: WifiManager
    private lateinit var etFileName: EditText
    private lateinit var etIndex: EditText
    private lateinit var btnStart: Button
    private lateinit var btnFinish: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private val PERMISSION_REQUEST_CODE = 100

    // BSSID → WifiInfo(SSID, freq, RSSI list)
    private val wifiInfoMap = mutableMapOf<String, WifiInfo>()
    // 측정 완료된 위치별 데이터 목록
    private val measurementData = mutableListOf<Measurement>()

    private var scanCount = 0
    private val totalScans = 3
    private val scanHandler = Handler(Looper.getMainLooper())

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 권한 체크
            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(applicationContext, "ACCESS_FINE_LOCATION 권한이 없습니다.", Toast.LENGTH_SHORT).show()
                return
            }
            val success = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
            if (success) {
                wifiManager.scanResults.forEach { result ->
                    val info = wifiInfoMap.getOrPut(result.BSSID) {
                        WifiInfo(result.SSID, result.frequency, mutableListOf())
                    }
                    info.levels.add(result.level)
                }
            } else {
                Toast.makeText(applicationContext, "Wi-Fi 스캔 실패", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_make_radiomap)

        etFileName = findViewById(R.id.etFileName)
        etIndex    = findViewById(R.id.etIndex)
        btnStart   = findViewById(R.id.btnStart)
        btnFinish  = findViewById(R.id.btnFinish)
        tvStatus   = findViewById(R.id.tvStatus)
        tvLog      = findViewById(R.id.tvLog)

        btnFinish.isEnabled = false
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // 권한 요청
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this,
                permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }

        btnStart.setOnClickListener {
            val index = etIndex.text.toString().trim()
            if (index.isEmpty()) {
                Toast.makeText(this, "위치 인덱스를 입력해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            wifiInfoMap.clear()
            scanCount = 0
            btnFinish.isEnabled = false
            tvStatus.text = "측정 시작합니다... (위치: $index)"
            startWifiScanning(index)
        }

        btnFinish.setOnClickListener {
            saveCsvFile()
            finish()
        }
    }

    private fun startWifiScanning(index: String) {
        val runnable = object : Runnable {
            override fun run() {
                Log.d("Make_RadioMap", "스캔 시도 $scanCount/$totalScans")
                val okFine = ContextCompat.checkSelfPermission(
                    this@Make_RadioMap, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val okNearby = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ContextCompat.checkSelfPermission(
                        this@Make_RadioMap, Manifest.permission.NEARBY_WIFI_DEVICES
                    ) == PackageManager.PERMISSION_GRANTED
                } else true

                if (okFine && okNearby) {
                    try {
                        val started = wifiManager.startScan()
                        Log.d("Make_RadioMap", "startScan() -> $started")
                        if (started) {
                            scanCount++
                            tvStatus.text = "Wi-Fi 스캔 중... ($scanCount/$totalScans)"
                            if (scanCount < totalScans) {
                                scanHandler.postDelayed(this, 4000)
                            } else {
                                processScanResults(index)
                            }
                        } else {
                            Log.e("Make_RadioMap", "startScan 실패")
                        }
                    } catch (e: SecurityException) {
                        Log.e("Make_RadioMap", "SecurityException: ${e.message}")
                    }
                } else {
                    Log.e("Make_RadioMap", "권한 부족")
                }
            }
        }
        scanHandler.post(runnable)
    }

    private fun processScanResults(index: String) {
        // –100 제외 후 평균 RSSI 계산
        val rssiMap = wifiInfoMap.mapValues { (_, info) ->
            val valid = info.levels.filter { it != -100 }
            if (valid.isNotEmpty()) valid.average().toInt() else -100
        }
        // SSID, frequency 정보도 함께 저장
        val infoMap = wifiInfoMap.mapValues { (_, info) ->
            info.ssid to info.frequency
        }
        measurementData.add(Measurement(index, rssiMap, infoMap))
        wifiInfoMap.clear()

        // 인덱스(x,y) 로그
        val coords = index.split(",")
        val x = coords.getOrNull(0) ?: "-"
        val y = coords.getOrNull(1) ?: "-"
        tvLog.text    = "마지막 저장 좌표: x=$x, y=$y"
        tvStatus.text = "측정 완료! 다음 위치를 입력해주세요."
        Toast.makeText(this, "측정이 완료되었습니다!", Toast.LENGTH_SHORT).show()

        etIndex.text.clear()
        btnFinish.isEnabled = true
    }

    private fun saveCsvFile() {
        val name = etFileName.text.toString().trim()
        if (name.isEmpty() || measurementData.isEmpty()) {
            Toast.makeText(this, "파일명 또는 데이터가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        // 모든 BSSID 수집
        val allBssids = measurementData
            .flatMap { it.rssiMap.keys }
            .distinct()
            .sorted()

        // CSV 파일 준비
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val fileName = "${name}_${timestamp}_Radiomap.csv"
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloads, fileName)

        try {
            FileWriter(file).use { writer ->
                // 1) 헤더: x, y, "MAC,대역,SSID"
                val header = mutableListOf("x", "y")
                allBssids.forEach { bssid ->
                    // 첫 번째 measurement 에서 SSID/ frequency 가져옴
                    val (ssid, freq) = measurementData
                        .mapNotNull { it.infoMap[bssid] }
                        .firstOrNull() ?: ("" to 0)
                    val band = if (freq >= 5000) "5.0" else "2.4"
                    header.add("\"$bssid,$band,$ssid\"")
                }
                writer.write(header.joinToString(",") + "\n")

                // 2) 각 위치별 RSSI 평균값만
                measurementData.forEach { m ->
                    val coords = m.index.split(",")
                    val x = coords.getOrNull(0) ?: ""
                    val y = coords.getOrNull(1) ?: ""
                    val row = mutableListOf(x, y)
                    allBssids.forEach { bssid ->
                        row.add(m.rssiMap[bssid]?.toString() ?: "-100")
                    }
                    writer.write(row.joinToString(",") + "\n")
                }
            }
            Toast.makeText(this, "CSV 저장됨: $fileName", Toast.LENGTH_LONG).show()
            measurementData.clear()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "CSV 저장 실패", Toast.LENGTH_SHORT).show()
        }
    }

    private fun unregisterReceiverSafe(receiver: BroadcastReceiver) {
        try { unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(wifiScanReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiverSafe(wifiScanReceiver)
    }
}
