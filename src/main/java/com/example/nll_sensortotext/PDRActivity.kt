package com.example.nll_sensortotext

import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.nll_sensortotext.databinding.ActivityPdrBinding
import kotlin.math.*

class PDRActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityPdrBinding
    private lateinit var sensorManager: SensorManager

    private var stepCount = 0          // 이번 세션 걸음 수
    private var totalStepCount = 0     // 앱 실행 이후 누적 걸음 수
    private var stepDetected = false

    private var heading = 0.0
    private var trajectory = mutableListOf<Pair<Float, Float>>() // (x, y) 좌표 저장
    private var posX = 0f
    private var posY = 0f

    private val stepThreshold = 11.0f   // 가속도 크기 threshold
    private val stepLength = 0.7f      // 평균 보폭 (m)
    private val meterScale = 80f       // 1m = 50px 로 스케일링

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        // 초기 위치 (0,0)
        trajectory.add(Pair(0f, 0f))

        binding.surfaceView.post {
            drawPath()
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager.registerListener(this,
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this,
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
            SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this,
            sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR),
            SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when(event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val accMag = sqrt(event.values[0].pow(2) +
                        event.values[1].pow(2) +
                        event.values[2].pow(2))

                // 간단한 peak 검출
                if (accMag > stepThreshold && !stepDetected) {
                    stepDetected = true
                    stepCount++
                    totalStepCount++

                    // 보폭만큼 위치 업데이트
                    posX += (stepLength * cos(Math.toRadians(heading))).toFloat()
                    posY += (stepLength * sin(Math.toRadians(heading))).toFloat()
                    trajectory.add(Pair(posX, posY))

                    // 최대 n걸음까지만 유지
                    if (trajectory.size > 30) trajectory.removeAt(0)

                    drawPath()
                }
                if (accMag < 9.8) stepDetected = false
            }

            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val rotMat = FloatArray(9)
                val orientVals = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                SensorManager.getOrientation(rotMat, orientVals)

                // 부호 반전 (왼쪽 회전 시 왼쪽으로 보이게)
                heading = -Math.toDegrees(orientVals[0].toDouble())
            }
        }

        binding.tvStep.text = "Steps: $stepCount (Total: $totalStepCount)"
        binding.tvHeading.text = "Heading: %.1f°".format(heading)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun drawPath() {
        val canvas = binding.surfaceView.holder.lockCanvas()
        canvas.drawColor(Color.WHITE)

        val width = binding.surfaceView.width
        val height = binding.surfaceView.height

        // 현재 위치를 화면 중심으로 이동시키기 위한 offset
        val offsetX = width / 2 - posX * meterScale
        val offsetY = height / 2 + posY * meterScale

        // ===== Grid =====
        val paintGrid = android.graphics.Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 1f
        }
        val paintLabel = android.graphics.Paint().apply {
            color = Color.DKGRAY
            textSize = 20f
        }

        // 그리드 범위: 화면 크기에 맞춰 +-N m
        val xRange = (-width/1 .. width/1 step meterScale.toInt())
        val yRange = (-height/1 .. height/1 step meterScale.toInt())

        for (dx in xRange step meterScale.toInt()) {
            val x = offsetX + dx
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), paintGrid)
            if (abs(dx/meterScale) < 50) { // label 너무 많아지지 않게 제한
                canvas.drawText("${(dx/meterScale).toInt()}m", x+2, offsetY-2, paintLabel)
            }
        }
        for (dy in yRange step meterScale.toInt()) {
            val y = offsetY - dy
            canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), paintGrid)
            if (abs(dy/meterScale) < 50) {
                canvas.drawText("${(dy/meterScale).toInt()}m", offsetX+4, y-4, paintLabel)
            }
        }

        // ===== Path Line =====
        val paintLine = android.graphics.Paint().apply {
            color = Color.BLUE
            strokeWidth = 4f
        }
        for (i in 1 until trajectory.size) {
            val (x1, y1) = trajectory[i-1]
            val (x2, y2) = trajectory[i]
            canvas.drawLine(
                offsetX + x1*meterScale, offsetY - y1*meterScale,
                offsetX + x2*meterScale, offsetY - y2*meterScale,
                paintLine
            )
        }

        // ===== Path Dots =====
        val paintDot = android.graphics.Paint().apply {
            color = Color.RED
            style = android.graphics.Paint.Style.FILL
        }
        for ((x, y) in trajectory) {
            canvas.drawCircle(
                offsetX + x*meterScale,
                offsetY - y*meterScale,
                6f,
                paintDot
            )
        }

        binding.surfaceView.holder.unlockCanvasAndPost(canvas)
    }
}
