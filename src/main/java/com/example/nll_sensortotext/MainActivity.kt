package com.example.nll_sensortotext

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectRequest
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var s3Client: AmazonS3Client
    private lateinit var editTextUserId: TextInputEditText

    companion object {
        private const val REQUEST_CODE_PICK_FILE = 1001

        // 전역 변수로 사용자 ID 저장 (다른 Activity에서도 사용 가능)
        var currentUserId: String = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // AWS S3 클라이언트 초기화
        initS3Client()

        // 1) Toolbar를 ActionBar로 설정
        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)

        // 2) 사용자 ID 입력 필드 참조
        editTextUserId = findViewById(R.id.editTextUserId)

        // 3) MaterialCardView 참조
        val cardWifi = findViewById<MaterialCardView>(R.id.cardWifi)
        val cardSensorData = findViewById<MaterialCardView>(R.id.cardSensorData)
        val cardSensorRadiomap = findViewById<MaterialCardView>(R.id.cardSensorRadiomap)
        val cardArduino = findViewById<MaterialCardView>(R.id.cardArduino)
        val cardPDR = findViewById<MaterialCardView>(R.id.cardPDR)
        val cardCloudUpload = findViewById<MaterialCardView>(R.id.cardCloudUpload)

        // 4) 클릭 리스너 연결
        cardWifi.setOnClickListener {
            updateUserId()
            startActivity(Intent(this, Make_RadioMap::class.java))
        }

        cardSensorData.setOnClickListener {
            updateUserId()
            startActivity(Intent(this, SensorData::class.java))
        }

        cardSensorRadiomap.setOnClickListener {
            updateUserId()
            startActivity(Intent(this, Make_Sensor_RadioMap::class.java))
        }

        cardArduino.setOnClickListener {
            updateUserId()
            startActivity(Intent(this, ArduinoLoggingActivity::class.java))
        }

        cardPDR.setOnClickListener {
            updateUserId()
            startActivity(Intent(this, PDRActivity::class.java))
        }

        // 클라우드 업로드 버튼
        cardCloudUpload.setOnClickListener {
            updateUserId()
            selectFileToUpload()
        }
    }

    /**
     * 사용자 ID 업데이트 (전역 변수에 저장)
     */
    private fun updateUserId() {
        currentUserId = editTextUserId.text.toString().trim()
    }

    /**
     * 현재 사용자 ID 가져오기 (다른 Activity에서 호출 가능)
     */
    fun getUserId(): String {
        return currentUserId
    }

    /**
     * AWS S3 클라이언트 초기화
     */
    private fun initS3Client() {
        try {
            val credentialsProvider = CognitoCachingCredentialsProvider(
                applicationContext,
                "ap-northeast-2:708f6ea7-59e2-4023-9497-1ea2689728ec", // Cognito Identity Pool ID
                Regions.AP_NORTHEAST_2 // 서울 리전
            )

            s3Client = AmazonS3Client(credentialsProvider, Region.getRegion(Regions.AP_NORTHEAST_2))

            Toast.makeText(this, "AWS 클라이언트 초기화 완료", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "AWS 초기화 실패: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    /**
     * 파일 선택기 열기
     */
    private fun selectFileToUpload() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "text/csv",
                    "text/comma-separated-values",
                    "application/csv"
                ))
            }
            startActivityForResult(intent, REQUEST_CODE_PICK_FILE)
        } catch (e: Exception) {
            Toast.makeText(this, "파일 선택기 열기 실패: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    /**
     * 파일 선택 결과 처리
     */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_PICK_FILE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                uploadFileToS3(uri)
            } ?: run {
                Toast.makeText(this, "파일 선택 실패", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * S3에 파일 업로드 (사용자 ID 기반 폴더 구조)
     */
    private fun uploadFileToS3(fileUri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(fileUri)
                if (inputStream == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "❌ 파일을 읽을 수 없습니다", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val originalFileName = getFileName(fileUri)

                // 사용자 ID 가져오기
                val userId = currentUserId.ifEmpty { "default" }

                // 파일명에 사용자 ID 추가
                val newFileName = if (currentUserId.isNotEmpty()) {
                    "${currentUserId}_$originalFileName"
                } else {
                    originalFileName
                }

                // S3 키 생성 (사용자 ID별 폴더)
                val s3Key = "$userId/$newFileName"

                // 업로드 시작 알림
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "📤 업로드 시작\n사용자: $userId\n파일: $newFileName",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // 메타데이터 설정
                val metadata = ObjectMetadata().apply {
                    contentType = "text/csv"
                    contentLength = inputStream.available().toLong()
                    // 사용자 ID를 메타데이터에도 추가
                    addUserMetadata("user-id", userId)
                }

                // S3 업로드 요청
                val putRequest = PutObjectRequest(
                    "nnl-sensor-data", // S3 버킷 이름
                    s3Key,
                    inputStream,
                    metadata
                )

                // 업로드 실행
                s3Client.putObject(putRequest)

                // 업로드 완료 알림
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "✅ 업로드 완료!\n폴더: $userId/\n파일: $newFileName",
                        Toast.LENGTH_LONG
                    ).show()
                }

                inputStream.close()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "❌ 업로드 실패: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                e.printStackTrace()
            }
        }
    }

    /**
     * URI에서 파일명 추출
     */
    private fun getFileName(uri: Uri): String {
        var result: String? = null

        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = it.getString(index)
                    }
                }
            }
        }

        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }

        return result ?: "sensor_data_${System.currentTimeMillis()}.csv"
    }
}