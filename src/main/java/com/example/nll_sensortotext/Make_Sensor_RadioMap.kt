package com.example.nll_sensortotext

import android.Manifest
import android.content.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.*
import android.os.Environment
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class Make_Sensor_RadioMap : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var wifiManager: WifiManager
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

    private var isCollecting = false
    private val sensorDataRows = mutableListOf<String>()

    // BSSID 기준으로 수집된 SSID, RSSI 및 주파수 값을 저장
    private val wifiScanResultsByTime = mutableListOf<Pair<Long, Map<String, Triple<String, Int, Int>>>>()
    private val allBssidSet = mutableSetOf<String>()

    private val handler = Handler(Looper.getMainLooper())
    private var lastAccel = FloatArray(3)
    private var lastGyro = FloatArray(3)
    private var lastMag = FloatArray(3)
    private var lastOrient = FloatArray(3)
    private var lastGameRot = FloatArray(4)
    private var lastPressure = 0f    // 기압센서 값 (hPa)

    private val sensorIntervalMs = 20L    // 50Hz
    private val wifiScanIntervalMs = 4000L // 4초마다 Wi-Fi 스캔

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val success = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
            if (success) saveWifiResults(wifiManager.scanResults)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_make_sensor_radiomap)

        btnStart = findViewById(R.id.btnStart)
        btnStop  = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        wifiManager  = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        btnStart.setOnClickListener { startCollection() }
        btnStop.setOnClickListener  { stopCollection() }

        // 위치 및 Wi-Fi 권한 요청
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
        ActivityCompat.requestPermissions(this, permissions, 0)
    }

    private fun startCollection() {
        if (isCollecting) return
        isCollecting = true

        sensorDataRows.clear()
        wifiScanResultsByTime.clear()
        allBssidSet.clear()
        tvStatus.text = "데이터 수집 중..."

        // 센서 리스너 등록 (50Hz)
        val sensors = arrayOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_ORIENTATION,
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_PRESSURE       // 기압센서 추가
        )
        sensors.forEach { type ->
            sensorManager.getDefaultSensor(type)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            }
        }

        handler.post(sensorRunnable)
        handler.post(wifiRunnable)
    }

    private fun stopCollection() {
        if (!isCollecting) return
        isCollecting = false

        try { sensorManager.unregisterListener(this) } catch (_: IllegalArgumentException) {}
        handler.removeCallbacksAndMessages(null)
        unregisterReceiverSafe(wifiScanReceiver)

        saveCsv()
        tvStatus.text = "데이터 수집 완료!"
    }

    private val sensorRunnable = object : Runnable {
        override fun run() {
            if (!isCollecting) return
            val timestamp = System.currentTimeMillis()
            // 타임스탬프, Acc, Gyro, Mag, Orientation, GameRot, Pressure 순
            val row = StringBuilder().apply {
                append(timestamp)
                append(",${lastAccel.joinToString(",")}")
                append(",${lastGyro.joinToString(",")}")
                append(",${lastMag.joinToString(",")}")
                append(",${lastOrient.joinToString(",")}")
                append(",${lastGameRot.joinToString(",")}")
                append(",${lastPressure}")
            }.toString()
            sensorDataRows.add(row)
            handler.postDelayed(this, sensorIntervalMs)
        }
    }

    private val wifiRunnable = object : Runnable {
        override fun run() {
            if (!isCollecting) return
            try { wifiManager.startScan() } catch (_: SecurityException) {}
            handler.postDelayed(this, wifiScanIntervalMs)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER         -> lastAccel  = it.values.clone()
                Sensor.TYPE_GYROSCOPE             -> lastGyro   = it.values.clone()
                Sensor.TYPE_MAGNETIC_FIELD        -> lastMag    = it.values.clone()
                Sensor.TYPE_ORIENTATION           -> lastOrient = it.values.clone()
                Sensor.TYPE_GAME_ROTATION_VECTOR  -> lastGameRot = it.values.clone()
                Sensor.TYPE_PRESSURE              -> lastPressure = it.values[0]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun saveWifiResults(results: List<ScanResult>) {
        val timestamp = System.currentTimeMillis()
        val map = mutableMapOf<String, Triple<String, Int, Int>>() // BSSID -> (SSID, RSSI, freqMHz)
        results.forEach { res ->
            map[res.BSSID] = Triple(res.SSID, res.level, res.frequency)
            allBssidSet.add(res.BSSID)
        }
        wifiScanResultsByTime.add(timestamp to map)
    }

    private fun saveCsv() {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        // 1) 센서 데이터 저장
        File(downloads, "SensorData_$timestamp.csv").apply {
            FileWriter(this).use { writer ->
                writer.write(
                    "Timestamp,AccX,AccY,AccZ,GyroX,GyroY,GyroZ,MagX,MagY,MagZ,OrientationAzimuth,OrientationPitch,OrientationRoll,GameRotX,GameRotY,GameRotZ,GameRotW,Pressure\n"
                )
                sensorDataRows.forEach { writer.write(it + "\n") }
            }
        }

        // 2) Wi-Fi 데이터 저장 (콤마 구분자)
        File(downloads, "WifiData_$timestamp.csv").apply {
            FileWriter(this).use { writer ->
                val delimiter = ","
                // 헤더: Timestamp 및 AP별 "MAC,대역,SSID"
                val headerParts = mutableListOf("Timestamp")
                val bssidList = allBssidSet.sorted()
                bssidList.forEach { bssid ->
                    // 첫 스캔부터 해당 AP 정보 찾기
                    val tri = wifiScanResultsByTime.mapNotNull { it.second[bssid] }.firstOrNull()
                    val band = tri?.third?.let { if (it >= 5000) "5.0" else "2.4" } ?: ""
                    val ssid = tri?.first ?: ""
                    headerParts.add("\"$bssid,$band,$ssid\"")
                }
                writer.write(headerParts.joinToString(delimiter) + "\n")

                // 각 행: Timestamp 및 RSSI 값
                for ((time, map) in wifiScanResultsByTime) {
                    val parts = mutableListOf(time.toString())
                    bssidList.forEach { bssid ->
                        val rssi = map[bssid]?.second ?: -100
                        parts.add(rssi.toString())
                    }
                    writer.write(parts.joinToString(delimiter) + "\n")
                }
            }
        }

        Toast.makeText(this, "CSV 저장 완료", Toast.LENGTH_LONG).show()
    }

    private fun unregisterReceiverSafe(receiver: BroadcastReceiver) {
        try { unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiverSafe(wifiScanReceiver)
    }
}
