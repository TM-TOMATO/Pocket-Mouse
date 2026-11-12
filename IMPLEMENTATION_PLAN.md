# DeskMouse VIO: 비주얼-관성 센서 융합 스마트폰 광학 데스크 마우스 시스템 설계서

## 1. 개요 및 시스템 아키텍처
스마트폰을 책상 바닥에 엎어두었을 때(전면 카메라가 책상면을 향함), 전면 카메라의 초근접 저조도 영상 스트림과 고주파 IMU 센서(자이로스코프, 선형 가속도계, 조도/근접)를 융합(VIO: Visual-Inertial Odometry)하여 정밀한 2차원 평면 이동 벡터를 실시간 추산하고, Android `BluetoothHidDevice` API를 통해 태블릿(Galaxy Tab, iPadOS 등)에 표준 Bluetooth HID 마우스 입력으로 중계하는 무선 광학 마우스 시스템이다.

```
+-----------------------------------------------------------------------------------+
|                              스마트폰 (Host Transmitter)                           |
|                                                                                   |
|  [ 전면 카메라 (CameraX) ]              [ 하드웨어 센서 (SensorManager) ]           |
|      (320x240 YUV_420_888)                - Gyroscope (TYPE_GYROSCOPE) 100Hz      |
|              |                            - Linear Acceleration 100Hz             |
|              v                            - Proximity (TYPE_PROXIMITY)            |
|  [ CLAHE 전처리 (대비 극대화) ]                    |                                   |
|              |                                    |                               |
|              v                                    v                               |
|  [ Lucas-Kanade 광학 흐름 계산 ]            [ ImuSensorProcessor ]                 |
|    - Raw (ΔX_opt, ΔY_opt)                 - Z축 회전 오차 상쇄 (k_rot * ω_z)       |
|    - Feature Confidence 수치               - Lift-off (들어올림) 감지 & Mute        |
|              \                               - 저특징점 감쇠 관성 보간 (최대 150ms)   |
|               \                                  /                                |
|                v                                v                                 |
|              [ SensorFusionController & One Euro Filter ]                         |
|                     - 회전 차감 보정 & Jitter 억제                                  |
|                     - 민감도(DPI) 배율 & 축 반전(X/Y)                               |
|                                     |                                             |
|  [ 물리 볼륨 버튼 인터셉터 ]          | (최종 ΔX, ΔY)                               |
|    - Vol Up: 좌클릭                 v                                             |
|    - Vol Down: 우클릭          [ BluetoothHidMouseManager ]                       |
|    - Both: 스크롤 모드           - 표준 4바이트 HID 마우스 패킷 전송                 |
|                                  - (Buttons, ΔX, ΔY, Wheel)                       |
+-----------------------------------------------------------------------------------+
                                      | Bluetooth HID (60~100Hz)
                                      v
                      +-------------------------------+
                      |   수신 기기 (태블릿 / PC)      |
                      |   - Galaxy Tab / iPadOS / Mac |
                      |   - 표준 Bluetooth 마우스 인식 |
                      +-------------------------------+
```

---

## 2. 5대 핵심 영역 상세 명세

### 1) 비주얼-관성 센서 융합 (Visual-Inertial Sensor Fusion) 파이프라인
* **초근접/저조도 전처리 (CLAHE):**
  - 전면 카메라를 책상 바닥에 밀착할 경우 초점이 맞지 않는 초근접(Out-of-focus) 흐림과 그림자로 인한 저조도 현상이 필연적으로 발생한다.
  - CameraX의 `ImageAnalysis`에서 320x240 (또는 480x360) YUV_420_888 포맷의 Y(Luminance, 단일 채널 그레이스케일) 버퍼를 다이렉트로 추출한다.
  - OpenCV `Imgproc.createCLAHE(clipLimit = 3.0, tileGridSize = Size(8, 8))`를 적용하여 국소 영역 간 극심한 명암비를 보정하고, 미세한 책상 결 및 종이 질감의 그라디언트를 증폭시킨다.
  - 전면 카메라 렌즈 인접 화면 상단 영역을 `#FFFFFF`로 최대 휘도 발광시켜 균일한 전방 간접 조명을 제공한다.

* **광학 흐름(Optical Flow) 추적:**
  - 8x8 격자 기반 유의미한 모서리 특징점(Good Features to Track, Harris Corner 또는 Shi-Tomasi)을 초기화하거나, 피라미드 Lucas-Kanade (`calcOpticalFlowPyrLK`) 알고리즘을 사용해 프레임 간 이동 벡터 $(\Delta X_{\text{opt}}, \Delta Y_{\text{opt}})$를 산출한다.
  - 추적 성공 상태 플래그(`status[i] == 1`) 및 에러 임계치를 기준으로 유효한 특징점의 비율을 계산하여 `Feature Confidence` ($0.0 \sim 1.0$)를 도출한다.
  - 평균값 대신 이상치(Outlier)에 강인한 RANSAC 또는 중앙값(Median of displacement) 필터를 적용하여 마우스 이동 벡터의 단일 대표값을 결정한다.

* **자이로스코프 Z축 회전 오차 상쇄 (Rotational Compensation):**
  - 손목을 축으로 마우스를 회전시킬 때, 카메라 렌즈의 위치가 스마트폰의 회전 중심(피벗점)에서 오프셋 거리 $R$만큼 떨어져 있으므로 회전 시 순수 평행 이동이 아님에도 불구하고 $\Delta X_{\text{opt}}$가 발생하는 아티팩트가 생긴다.
  - `Sensor.TYPE_GYROSCOPE`로부터 스마트폰 수평면 기준 Z축 각속도($\omega_z$, rad/s)를 100Hz 샘플링한다.
  - 렌즈 오프셋 거리 및 카메라 화각(FOV)에 대응하는 회전 상쇄 계수 $k_{\text{rot}}$를 정의하고 다음 수식으로 선형 이동량을 보정한다:
    $$\Delta X_{\text{corr}} = \Delta X_{\text{opt}} - (k_{\text{rot}} \cdot \omega_z \cdot \Delta t)$$
    $$\Delta Y_{\text{corr}} = \Delta Y_{\text{opt}}$$
  - $k_{\text{rot}}$는 대시보드 UI 슬라이더를 통해 기기별/파지법별로 캘리브레이션 가능하도록 설계한다.

* **가속도계 Lift-off(들어올림) 감지 및 자동 음소거 (Auto-Mute):**
  - 마우스 패드 경계에 도달해 폰을 살짝 들어 제자리로 옮기는 'Lift-off' 동작 시 바닥 영상이 변하거나 손가락 그림자가 스치며 커서가 크게 튀는 현상을 원천 차단한다.
  - `Sensor.TYPE_LINEAR_ACCELERATION`의 Z축 가속도($a_z$)의 미분(Jerk) 및 절댓값 임계치($|a_z| > 2.5\,\text{m/s}^2$) 감시.
  - 동시에 `Sensor.TYPE_PROXIMITY` 값이 0이 아니거나(물체 떨어짐) 이미지의 평균 밝기/특징점 일관성이 깨지는 순간 즉시 `isLiftOff = true` 상태로 진입.
  - Lift-off 상태에서는 커서 이동 패킷 전송을 0으로 강제 음소거(Mute)하고, 폰이 다시 바닥에 안착(안정화 시간 100ms)된 후 추적을 재개한다.

* **무늬 없는 구간(Low-Feature) 단기 관성 보간 (Damped Inertial Interpolation):**
  - 유리, 코팅된 단색 매트 등 특징점이 임계치(예: 유효 특징점 < 5개) 이하로 떨어지는 경우:
  - 가속도계의 이중 적분은 순식간에 발산(Drift)하므로, **최대 150ms**까지만 직전 유효 이동 속도 벡터 $(\vec{v}_x, \vec{v}_y)$를 지수 감쇠($e^{-\lambda t}$)시켜 부드럽게 감속 보간한다.
  - 150ms 이상 특징점 회복이 불가능할 경우 이동량을 즉시 0으로 수렴시키고, UI에 `LOW_TEXTURE_WARNING` 플래그를 발생시켜 사용자에게 마우스패드 사용을 권고한다.

* **One Euro Filter 스무딩:**
  - 정지 상태에서의 미세 손떨림/센서 노이즈(Jitter)를 억제하면서, 빠른 플릭(Flick) 동작에서는 지연 시간(Lag)이 없도록 $f_c = f_{c,\min} + \beta \cdot |\dot{x}|$ 동적 컷오프 주파수를 갖는 **1€ Filter(One Euro Filter)**를 최종 출력단에 배치한다.

---

### 2) Bluetooth HID 태블릿 통신 계층 (`BluetoothHidMouseManager`)
* **표준 마우스 HID Report Descriptor:**
  ```c
  0x05, 0x01, // USAGE_PAGE (Generic Desktop)
  0x09, 0x02, // USAGE (Mouse)
  0xa1, 0x01, // COLLECTION (Application)
  0x09, 0x01, //   USAGE (Pointer)
  0xa1, 0x00, //   COLLECTION (Physical)
  0x05, 0x09, //     USAGE_PAGE (Button)
  0x19, 0x01, //     USAGE_MINIMUM (Button 1: Left)
  0x29, 0x03, //     USAGE_MAXIMUM (Button 3: Middle)
  0x15, 0x00, //     LOGICAL_MINIMUM (0)
  0x25, 0x01, //     LOGICAL_MAXIMUM (1)
  0x95, 0x03, //     REPORT_COUNT (3)
  0x75, 0x01, //     REPORT_SIZE (1)
  0x81, 0x02, //     INPUT (Data, Var, Abs)
  0x95, 0x01, //     REPORT_COUNT (1)
  0x75, 0x05, //     REPORT_SIZE (5 - Padding)
  0x81, 0x03, //     INPUT (Const, Var, Abs)
  0x05, 0x01, //     USAGE_PAGE (Generic Desktop)
  0x09, 0x30, //     USAGE (X)
  0x09, 0x31, //     USAGE (Y)
  0x09, 0x38, //     USAGE (Wheel)
  0x15, 0x81, //     LOGICAL_MINIMUM (-127)
  0x25, 0x7f, //     LOGICAL_MAXIMUM (127)
  0x75, 0x08, //     REPORT_SIZE (8)
  0x95, 0x03, //     REPORT_COUNT (3)
  0x81, 0x06, //     INPUT (Data, Var, Rel)
  0xc0,       //   END_COLLECTION
  0xc0        // END_COLLECTION
  ```
* **4바이트 패킷 인코딩:**
  - `Byte 0`: `(Bit 0: Left, Bit 1: Right, Bit 2: Middle, Bits 3-7: 0)`
  - `Byte 1`: $\Delta X$ (signed byte, $-127 \sim +127$ 클램핑)
  - `Byte 2`: $\Delta Y$ (signed byte, $-127 \sim +127$ 클램핑)
  - `Byte 3`: $\Delta \text{Wheel}$ (signed byte, $-127 \sim +127$ 클램핑)
* **Android `BluetoothProfile.ServiceListener` & `BluetoothHidDevice` 등록:**
  - Android 9+ (API 28+) 공식 지원되는 `BluetoothProfile.HID_DEVICE`를 획득.
  - SDP 설정: Provider "Android VIO", Name "DeskMouse Optical", Description "Desk Optical Mouse VIO".
  - Subclass: `0x00` (Pointing Device - Mouse).
  - 페어링된 기기 상태 변경 리스너(`onConnectionStateChanged`)를 감지하여 연결된 태블릿(`BluetoothDevice`) 인스턴스를 관리하고, 주기 루프(Coroutine Dispatchers.Default 기반 60Hz~100Hz 타이머)로 `sendReport()` 호출.

---

### 3) 바닥 주행 특화 하드웨어 UX
* **상단 고휘도 조명 레이어 (Optical Illumination Layer):**
  - 마우스 주행 모드 활성화 시, 화면 상단 15~20% 영역을 순백색 `#FFFFFF` 캔버스로 채우고 기기 윈도우 밝기를 `WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL` (1.0f)로 고정한다.
  - 전면 카메라 렌즈 바로 옆에서 빛을 책상 면으로 강제 투사하여 카메라 셔터 스피드를 확보하고 모션 블러를 억제한다.
* **물리 볼륨 버튼 인터셉터 (`dispatchKeyEvent`):**
  - 화면이 바닥을 마주하고 있으므로 터치 클릭이 불가능하다.
  - 폰 측면의 물리 버튼을 활용:
    - `KEYCODE_VOLUME_UP`: 좌클릭 (누를 때 Button bit 0 = 1, 뗄 때 bit 0 = 0)
    - `KEYCODE_VOLUME_DOWN`: 우클릭 (누를 때 Button bit 1 = 1, 뗄 때 bit 1 = 0)
    - **동시 입력 시 (Scroll Mode):** 볼륨 상/하를 동시에 누르고 있는 동안에는 마우스의 Y축 이동량을 커서 이동 대신 `Wheel` 바이트로 전환하여 긴 문서/웹페이지 고속 스크롤을 구현한다.
  - 키 이벤트 소비 후 `return true`로 시스템 볼륨 조절 다이얼로그 노출을 차단한다.
* **오터치 및 전원 관리 방어:**
  - 주행 중 화면 꺼짐 방지: `FLAG_KEEP_SCREEN_ON`.
  - 책상면 마찰이나 미세 먼지로 인한 오터치를 차단하기 위해 일반 터치 이벤트는 무시하고, 주행 모드 해제는 **3초 롱프레스 프로그레스 홀드 버튼**으로만 안전하게 종료되도록 한다.

---

### 4) Jetpack Compose UI/UX 구조
1. **메인 대시보드 화면 (`DashboardScreen`):**
   - **연결 상태 카드:** 블루투스 활성화 여부, HID 프로파일 등록 상태, 페어링된 태블릿 이름(`BluetoothDevice.name`), 배터리 상태.
   - **실시간 센서 & VIO 디버그 패널:**
     - 전면 카메라 실시간 분석 뷰 (CLAHE 적용 흑백 영상 + 특징점 녹색 원 표시 + 광학 흐름 벡터 화살표 + 자이로 회전 상쇄 벡터 오버레이).
     - 현재 FPS, 추적 신뢰도(`Feature Confidence`), 감지된 모션 상태(Tracking / Lift-off / Low Feature).
   - **조정 및 캘리브레이션 슬라이더:**
     - 커서 민감도 (DPI 배율: 0.2x ~ 5.0x).
     - 자이로 회전 상쇄 계수 ($k_{\text{rot}}$: 0.0 ~ 3.0).
     - X축 / Y축 반전 토글 스위치 (iPadOS 자연스러운 스크롤/방향 지원).
     - One Euro Filter 최소 컷오프 주파수 / 감도 설정.
   - **주행 모드 진입 FAB:** "바닥 주행 모드 시작 (Start Mouse Mode)"
2. **바닥 주행 모드 화면 (`DeskDrivingScreen`):**
   - 상단 1/4: **초고휘도 백색 발광 바 (`#FFFFFF`)**.
   - 중앙: 고대비 상태 아이콘 및 라벨
     - 초록색: `정상 추적 중 (ACTIVE TRACKING)`
     - 주황색: `마우스 들어올림 (LIFT-OFF MUTED)`
     - 빨간색: `바닥 무늬 부족 (LOW TEXTURE)`
     - 파란색: `스크롤 모드 활성 (SCROLL MODE)`
   - 하단: **3초 프로그레스 롱프레스 홀드 종료 버튼** (원형 게이지가 3초간 차오르면 대시보드로 복귀).

---

### 5) 권한 및 예외 처리
* **권한 명세:**
  - `android.permission.CAMERA`
  - `android.permission.BLUETOOTH`
  - `android.permission.BLUETOOTH_ADMIN`
  - `android.permission.BLUETOOTH_CONNECT` (Android 12+)
  - `android.permission.BLUETOOTH_ADVERTISE` (Android 12+)
  - `android.permission.BLUETOOTH_SCAN` (Android 12+)
  - `android.permission.HIGH_SAMPLING_RATE_SENSORS`
  - `android.permission.WAKE_LOCK`
* **폴백 메커니즘:**
  - 자이로스코프 또는 선형가속도계 미지원 기기: 순수 광학 흐름(Pure Optical Flow) 모드로 자동 폴백.
  - 블루투스 미지원 또는 HID Host 미연결: 시뮬레이션 및 로컬 마우스 궤적 테스트 모드 지원.
  - 저조도 감지 시 경고 알림.
