# 📡 NLL_SensorToText

### 📱 Android 기반 센서 로깅 & Wi-Fi 라디오맵 생성 도구

---

## 🧭 앱 목적

이 앱은 실내 위치 측위 및 PDR(Pedestrian Dead Reckoning) 연구를 위한 **센서 기반 데이터 수집 도구**입니다.  
스마트폰의 다양한 센서 데이터를 로깅하고, Wi-Fi RSSI 값을 기반으로 한 **라디오맵(Radio Map)** 을 생성할 수 있습니다.

---

## ⚙️ 주요 기능

### 1️⃣ IMU + GPS 센서 데이터 로깅

- ⏱ **수집 주기**:  
  - 센서 데이터: 50Hz (`20ms` 간격)  
  - GPS: 1Hz (`1초 간격`으로 최신 값 유지)

- 📦 **저장되는 센서 종류**
  | 센서명                | Sensor Type | 설명 |
  |----------------------|-------------|------|
  | Accelerometer        | TYPE_ACCELEROMETER | x, y, z 축의 가속도 (기기 기준) |
  | Gyroscope            | TYPE_GYROSCOPE | x, y, z 축의 각속도 |
  | Magnetometer         | TYPE_MAGNETIC_FIELD | 지자기 필드 값 |
  | Orientation (Game RV)| TYPE_GAME_ROTATION_VECTOR | 방향성 정보 (Yaw, Pitch, Roll) |
  | Pressure Sensor      | TYPE_PRESSURE | 기압 센서 (기고도 추정 가능) |
  | GPS                  | LocationManager | 위도, 경도, 고도, 속도 |

- 🗂 **CSV 형식 저장**
  - 저장 경로: `Downloads/SensorData/`
  - 파일명: `sensor_data_yyyyMMdd_HHmmss.csv`
  - 종료 시 원하는 이름으로 저장 가능

---

### 2️⃣ Wi-Fi 라디오맵 생성

- 📶 **Wi-Fi 스캔 및 RSSI 측정**
  - 측정 시작 시 가장 강한 10개의 AP 자동 선정
  - 선택된 AP에 대해서만 RSSI 값 수집
  - 각 RP(Reference Point)에서 3회 측정 (4초 간격 → 총 12초)

- 📂 **CSV 파일 저장**
  - 저장 경로: `Downloads/`
  - 파일명: 사용자 지정 + 시간 정보가 포함된 이름

---

## 📝 사용 방법

### ✅ IMU 로깅

1. 앱 실행 후 `파일명`을 입력
2. GPS가 수신되면 자동으로 센서 데이터 수집 시작
3. `측정 중지` 버튼을 누르면 저장 완료

### ✅ Wi-Fi 라디오맵

1. `파일명`, `위치 인덱스`를 입력
2. `측정 시작` → 자동으로 3회 측정 후 저장 대기
3. 측정이 완료되면 `측정 완료` 버튼 클릭하여 파일 저장

---

## 📄 CSV 저장 형식

### ✅ IMU + GPS 데이터

### IMU + GPS 데이터 (CSV)
timestamp,acc_x,acc_y,acc_z,gyro_x,gyro_y,gyro_z,yaw,pitch,roll,latitude,longitude 
1712345678912,0.01,9.81,0.05,-0.02,0.01,0.00,30.1,2.0,0.3,37.12345,127.12345 ...

- **Time**: 측정 시간 (millisecond precision)
- **Accel_***: x, y, z 축 가속도
- **Gyro_***: x, y, z 축 각속도
- **Mag_***: x, y, z 지자기
- **Orient_***: 방향 (Yaw, Pitch, Roll 성격)
- **Pressure**: 기압
- **GPS**: 위도, 경도, 고도, 속도

---

### ✅ Wi-Fi 라디오맵

### Wi-Fi 라디오맵 (CSV)
Index,AP1,AP2,AP3,AP4,AP5,AP6,AP7,AP8,AP9,AP10 
1,-42,-51,-60,-70,-100,-100,-100,-100,-100,-100 

- `Index`: 측정 위치 번호
- `APn`: 모든 AP 대한 RSSI (신호가 없을 경우 -100 기록, 한 위치에서 3번 연속 측정 후, -100을 제외한 평균값 저장, MAC Address 함께 저장(열 이름))

---

### 3️⃣ Arduino Nano 33 BLE 센서 로깅

- 🔗 **BLE(Bluetooth Low Energy) 통신** 기반 실시간 센서 수신
- ✅ 스마트폰에서 `start/stop` 명령을 전송하여 Arduino의 센서 데이터 수집 제어
- ⏱ **수집 주기**: 약 20Hz
- 📄 **저장 형식 (CSV)**: `timestamp,ax,ay,az,gx,gy,gz`

- 📂 **저장 경로**:  
  `Downloads/sensordata_yyyyMMdd_HHmmss.csv`

> ※ 센서 측정은 Arduino Nano 33 BLE (BMI270) 보드 기준이며,  
> BLE UUID 및 수신 포맷은 앱과 Arduino 코드에서 커스터마이징 가능

---


### 4️⃣ IMU, RSSI 동시로깅 기능
- 🕒 **센서 50 Hz 로깅**  
- Accelerometer, Gyroscope, Magnetometer, Orientation, GameRotationVector, Pressure 센서를 20 ms 간격으로 수집  
- 각 샘플에 `Timestamp`를 부여하여 `SensorData_yyyyMMdd_HHmmss.csv` 로 저장  

- 📶 **Wi-Fi 4 s 스캔**  
- 4초마다 Wi-Fi 스캔을 실행하여 AP 당 BSSID, SSID, RSSI, 주파수 대역(2.4/5.0 GHz)을 획득  
- 스캔 결과를 `WifiData_yyyyMMdd_HHmmss.csv` 에 저장  
- 헤더: `"MAC,주파수(2.4/5.0),SSID"` (큰따옴표로 묶어 하나의 셀에 표기)  
- 데이터 행: 해당 AP의 **RSSI 값**만 콤마로 구분하여 기록  

- 📂 **파일 저장 경로**  
- `Downloads/` 폴더에 두 개의 CSV 파일 생성  
- `SensorData_…` (센서 전체 로그)  
- `WifiData_…`   (Wi-Fi RSSI 로그)

---


### ✅ Arduino BLE 로깅

1. Arduino Nano 33 BLE 보드에서 전원이 켜지고 BLE가 브로드캐스트되면
2. Android 앱에서 `BLE 연결` 버튼 클릭
3. 연결 후 `시작` 버튼으로 센서 수집 시작 → `중지` 버튼으로 종료
4. CSV 파일이 자동 생성되어 `Downloads` 폴더에 저장됨

## 📌 참고사항

- Android 12 이상에서는 다음 권한이 필요합니다:
  - `NEARBY_WIFI_DEVICES`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`
- 데이터는 `Downloads` 또는 `Downloads/SensorData`에 저장됩니다.
- 실험 전 기기의 센서 및 GPS가 정상 동작하는지 확인하세요.

---


### 5️⃣ PDR Path 시연 기능

- 👣 **걸음 검출**
  - Accelerometer threshold + Android StepDetector API (지원 기기)
  - 보폭 0.7 m 기준으로 이동 거리 업데이트
  - Heading 값은 **Game Rotation Vector** 센서를 이용해 추정

- 🧭 **Heading 추정**
  - `TYPE_GAME_ROTATION_VECTOR` 센서로 azimuth(방위각) 계산
  - 왼쪽/오른쪽 회전 방향이 실제 동작과 일치하도록 좌표계 보정 적용

- 🗺 **경로 시각화**
  - `SurfaceView` 기반으로 .-.-. 형태의 경로를 실시간으로 그림
  - 1m 단위 Grid 및 좌표 label 표시
  - 최대 30걸음까지만 최근 경로 유지
  - 현재 위치를 항상 화면 중심에 표시하여 **미니맵처럼 이동 경로 확인 가능**

- 📊 **화면 표시**
  - 상단에 현재 Step 수, 누적 Step 수, Heading 값을 실시간 갱신
  - 실행 직후에도 Grid가 표시되며, 걸음 발생 시 경로가 갱신됨



## 👨‍💻 개발자

- 김보성 (Boseong Kim)  
- GitHub: [GitBosung](https://github.com/GitBosung)

