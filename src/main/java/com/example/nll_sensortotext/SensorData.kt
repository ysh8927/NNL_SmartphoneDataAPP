package com.example.nll_sensortotext

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Environment
import android.preference.PreferenceManager
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class SensorData : AppCompatActivity(), SensorEventListener {
    // UI
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnSave: Button

    // 지도
    private lateinit var map: MapView
    private val pathPoints = mutableListOf<GeoPoint>()

    // 센서
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var gameRotationVector: Sensor? = null
    private var magnetometer: Sensor? = null
    private var pressureSensor: Sensor? = null

    // GPS
    private lateinit var locationManager: LocationManager
    private var currentLocation: Location? = null
    private var isGpsInitialized = false
    private var pendingStart = false

    // CSV 로깅
    private lateinit var csvFile: File
    private lateinit var csvWriter: FileWriter
    private var sensorExecutor: ScheduledExecutorService? = null

    // 샘플 카운터 (50Hz 센서 중 1Hz GPS 기록용)
    private var sampleCount = 0L

    // 센서 버퍼
    private val accelValues = FloatArray(3)
    private val gyroValues = FloatArray(3)
    private val orientationValues = FloatArray(3)
    private val magnetometerValues = FloatArray(3)
    private var pressureValue = 0f

    // 위치 권한 요청
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            initMap()
            initGps()
        } else {
            Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance()
            .load(this, PreferenceManager.getDefaultSharedPreferences(this))

        setContentView(R.layout.activity_sensordata)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // UI 바인딩
        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        btnStop  = findViewById(R.id.btnStop)
        btnSave  = findViewById(R.id.btnSave)
        map      = findViewById(R.id.osmMap)

        btnStart.setOnClickListener { startCollection() }
        btnStop.setOnClickListener  { stopCollection() }
        btnSave.setOnClickListener  { saveCollection() }

        // 센서 초기화
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer      = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope          = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gameRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        magnetometer       = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        pressureSensor     = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        listOf(accelerometer, gyroscope, gameRotationVector, magnetometer, pressureSensor)
            .forEach { it?.let { s -> sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST) } }

        // GPS 매니저 준비
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // 위치 권한 체크 및 요청
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            initMap()
            initGps()
        }
    }

    /** OSMDroid 지도 초기화 */
    private fun initMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(18.0)
        map.controller.setCenter(GeoPoint(37.5665, 126.9780))
    }

    /** GPS 업데이트 요청 */
    @SuppressLint("MissingPermission")
    private fun initGps() {
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, 1000L, 1f, gpsListener
        )
    }

    /** GPS 콜백 */
    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            currentLocation = loc

            if (!isGpsInitialized) {
                // 최초 GPS fix 시
                isGpsInitialized = true
                Toast.makeText(this@SensorData, "GPS 초기화 완료", Toast.LENGTH_SHORT).show()
                // pendingStart 상태라면 실제 로깅 시작
                if (pendingStart) {
                    pendingStart = false
                    actualStartLogging()
                }
            }

            // 로깅 중일 때만 경로 그리기
            if (sensorExecutor != null && !sensorExecutor!!.isShutdown) {
                val gp = GeoPoint(loc.latitude, loc.longitude)
                pathPoints.add(gp)
                drawPath()
            }
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /** 경로 폴리라인 그리기 */
    private fun drawPath() {
        map.overlays.removeAll { it is Polyline }
        val line = Polyline().apply {
            setPoints(pathPoints)
            width = 5f
        }
        map.overlays.add(line)
        pathPoints.lastOrNull()?.let { map.controller.animateTo(it) }
        map.invalidate()
    }

    /** 수집 시작 요청 */
    private fun startCollection() {
        // 1) 버튼 중복 클릭 방지
        btnStart.isEnabled = false

        if (isGpsInitialized) {
            // 2) 이미 GPS 준비 완료 상태면 바로 로깅 시작
            actualStartLogging()
        } else {
            // 3) GPS 초기화를 기다려야 하는 경우
            pendingStart = true
            tvStatus.text = "상태: GPS 초기화 대기..."
            btnStop.isEnabled  = false
            btnSave.isEnabled  = false
        }
    }

    /** GPS 초기화 후 실제 로깅 시작 */
    private fun actualStartLogging() {
        csvFile = createCsvFile()
        csvWriter = FileWriter(csvFile).apply {
            append("Time,Accel_X,Accel_Y,Accel_Z,Gyro_X,Gyro_Y,Gyro_Z,Mag_X,Mag_Y,Mag_Z,Orient_X,Orient_Y,Orient_Z,Pressure,Lat,Lng,Alt,Speed\n")
        }
        sampleCount = 0L
        sensorExecutor = Executors.newSingleThreadScheduledExecutor().apply {
            scheduleAtFixedRate({ logSensorDataSafely() }, 0, 20, TimeUnit.MILLISECONDS)
        }

        pathPoints.clear()
        tvStatus.text = "상태: 수집 중..."
        // 로깅 중에는 Stop 버튼만 활성화
        btnStop.isEnabled = true
        Toast.makeText(this, "로깅 시작", Toast.LENGTH_SHORT).show()
    }

    /** 수집 중지 */
    private fun stopCollection() {
        sensorExecutor?.shutdownNow()
        tvStatus.text = "상태: 수집 중지"
        btnStop.isEnabled = false
        btnSave.isEnabled  = true
    }

    /** 저장 완료 */
    private fun saveCollection() {
        try {
            csvWriter.close()
            Toast.makeText(this, "저장 완료: ${csvFile.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        tvStatus.text = "상태: 대기 중"
        btnStart.isEnabled = true
        btnSave.isEnabled  = false
    }

    /** 다운로드 폴더에 CSV 파일 생성 */
    private fun createCsvFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Seoul")
        }.format(Date())

        val downloadsDir = Environment
            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        return File(downloadsDir, "sensor_data_$stamp.csv")
    }

    /** 쓰레드 안전 호출 */
    private fun logSensorDataSafely() {
        if (::csvWriter.isInitialized) logSensorData()
    }

    /** 로그 작성 (샘플 1, 51, 101… 번째에 GPS 포함) */
    private fun logSensorData() {
        sampleCount++

        val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.KOREA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Seoul")
        }.format(Date())

        val gps = if (sampleCount % 50 == 1L && currentLocation != null) {
            "${currentLocation!!.latitude}," +
                    "${currentLocation!!.longitude}," +
                    "${currentLocation!!.altitude}," +
                    "${currentLocation!!.speed}"
        } else {
            " , , , "
        }

        val line = "$t," +
                "${accelValues.joinToString(",")}," +
                "${gyroValues.joinToString(",")}," +
                "${magnetometerValues.joinToString(",")}," +
                "${orientationValues.joinToString(",")}," +
                "$pressureValue," +
                gps + "\n"

        try {
            csvWriter.append(line)
            csvWriter.flush()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    // SensorEventListener
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER        ->
                System.arraycopy(event.values, 0, accelValues, 0, 3)
            Sensor.TYPE_GYROSCOPE            ->
                System.arraycopy(event.values, 0, gyroValues, 0, 3)
            Sensor.TYPE_GAME_ROTATION_VECTOR ->
                System.arraycopy(event.values, 0, orientationValues, 0, 3)
            Sensor.TYPE_MAGNETIC_FIELD       ->
                System.arraycopy(event.values, 0, magnetometerValues, 0, 3)
            Sensor.TYPE_PRESSURE             ->
                pressureValue = event.values[0]
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onResume() {
        super.onResume()
        map.onResume()
    }
    override fun onPause() {
        super.onPause()
        map.onPause()
    }
    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        sensorExecutor?.shutdownNow()
        if (::csvWriter.isInitialized) csvWriter.close()
    }
}
