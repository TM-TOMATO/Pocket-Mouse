# DeskMouse VIO: 작업 분해 및 하네스 검증 체크리스트 (`TASK_CHECKLIST.md`)

## 마일스톤 개요 및 진행 현황

- [x] **Milestone 1: 빌드 환경 및 필수 매니페스트 구성**
  - [x] 1.1 `gradle/libs.versions.toml` 및 `app/build.gradle.kts`에 CameraX 및 OpenCV Android SDK(`org.opencv:opencv:4.12.0`) 추가
  - [x] 1.2 `AndroidManifest.xml`에 필수 권한 선언 (`CAMERA`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `HIGH_SAMPLING_RATE_SENSORS` 등)
  - [x] 1.3 하드웨어 피처 선언 (`android.hardware.camera.any`, `android.hardware.bluetooth_le`)
  - **Verification Gate 1:**
    - [x] `compile_applet` 빌드 성공 (PASS)
    - [x] `AndroidManifest.xml` 구문 검사 및 네임스페이스 일치 확인 (PASS)

- [x] **Milestone 2: Bluetooth HID 통신 계층 (`BluetoothHidMouseManager`) 구현 및 단위 테스트**
  - [x] 2.1 표준 3버튼 + 스크롤 휠 마우스 HID Report Descriptor 바이트 배열 정의 (`HidConstants.kt`)
  - [x] 2.2 패킷 인코더 작성: 버튼 비트마스크(Left, Right, Middle) 및 $\Delta X, \Delta Y, \Delta \text{Wheel}$ (-127 ~ +127 클램핑) (`MouseReportPacket.kt`)
  - [x] 2.3 `BluetoothHidDevice.Callback` 및 `BluetoothProfile.ServiceListener` 연동 생명주기 관리 (`BluetoothHidMouseManager.kt`)
  - [x] 2.4 HID 연결 상태(`StateFlow<HidConnectionState>`) 발행
  - [x] 2.5 60Hz~100Hz 주기 전송 루프 코루틴 구현 (~83Hz 전송 루프)
  - **Verification Gate 2:**
    - [x] 단위 테스트 (`BluetoothHidPacketTest.kt`): 패킷 경계값 클램핑(-200 -> -127, +300 -> +127, 0 -> 0) 및 버튼 비트 연산 검증 통과 (PASS)
    - [x] `compile_applet` 및 단위 테스트 실행 확인 (`BUILD SUCCESSFUL` 통과)

- [x] **Milestone 3: IMU 센서 처리기 (`ImuSensorProcessor`) 구현 및 단위 테스트**
  - [x] 3.1 `SensorEventListener` 등록 (`TYPE_GYROSCOPE`, `TYPE_LINEAR_ACCELERATION`, `TYPE_PROXIMITY`)
  - [x] 3.2 Z축 각속도($\omega_z$) 수집 및 시간 미소 적분 ($\omega_z \cdot \Delta t$) (`handleGyroEvent`, `consumeAccumulatedRotationZ`)
  - [x] 3.3 Lift-off 감지기: Z축 선형 가속도 임계치($|a_z| > 2.5\,\text{m/s}^2$) 및 근접 센서 융합 상태 머신 (`handleLinearAccelEvent`)
  - [x] 3.4 무늬 부족(Low-Feature) 시 최대 150ms 지수 감쇠($e^{-\lambda t}$) 관성 속도 보간기 구현 (`computeInertialInterpolation`)
  - **Verification Gate 3:**
    - [x] 단위 테스트 (`ImuSensorProcessorTest.kt`): 합성 자이로 데이터 회전 변위량 산출, Lift-off 트리거링 및 안착 자동 복구, 150ms 초과 시 속도 0 강제 수렴 검증 통과 (PASS)

- [x] **Milestone 4: 광학 흐름 엔진 및 센서 융합 제어기 (`OpticalFlowEngine` & `SensorFusionController`)**
  - [x] 4.1 OpenCV CLAHE 전처리 파이프라인 (YUV 420 888 Y-plane -> CLAHE contrast enhancement)
  - [x] 4.2 Lucas-Kanade 광학 흐름 및 중앙값(Median) 변위 산출기 구현 (이상치 및 먼지 노이즈 제거)
  - [x] 4.3 자이로 Z축 회전 오차 상쇄 ($\Delta X_{\text{corr}} = \Delta X_{\text{opt}} - k_{\text{rot}} \cdot \omega_z \cdot \Delta t$)
  - [x] 4.4 One Euro Filter (동적 컷오프 주파수 지터 방지 필터) 2D 적용 (`OneEuroFilter2D.kt`)
  - [x] 4.5 `ImageProxy` 안전 해제 (`imageProxy.close()`) `finally` 블록 보장 (메모리 및 프레임 드롭 방지)
  - **Verification Gate 4:**
    - [x] `compile_applet` 성공 (PASS)
    - [x] CameraX 이미지 분석 파이프라인 `finally { imageProxy.close() }` 보장 확인 (PASS)
    - [x] 센서 융합 및 스크롤 모드 수식 단위 테스트 (`SensorFusionControllerTest.kt`) 통과 (PASS)

- [x] **Milestone 5: 물리 볼륨 버튼 인터셉터 및 Jetpack Compose UI 구현**
  - [x] 5.1 `MainActivity`의 `dispatchKeyEvent` 오버라이드: Volume Up(좌클릭), Volume Down(우클릭), 동시 입력(스크롤 모드)
  - [x] 5.2 볼륨 다이얼로그 무시 및 마우스 버튼 상태 Flow 갱신
  - [x] 5.3 M3 Jetpack Compose 메인 대시보드 (`DashboardScreen`):
    - Bluetooth/HID 상태 카드, 태블릿 연결 상태
    - 실시간 카메라 프리뷰 + 광학 흐름 & 자이로 보정 벡터 오버레이
    - 민감도(DPI), $k_{\text{rot}}$, X/Y 반전 슬라이더 및 스위치
  - [x] 5.4 바닥 주행 모드 화면 (`SurfaceDrivingScreen`):
    - 상단 초고휘도 발광 바 (`#FFFFFF`, Window Brightness 1.0f)
    - 중앙 고대비 상태 인디케이터 (정상 추적 / Lift-off / 무늬 부족 / 스크롤 모드)
    - 하단 3초 롱프레스 프로그레스 홀드 종료 버튼
  - [x] 5.5 화면 꺼짐 방지 (`FLAG_KEEP_SCREEN_ON`) 및 바닥 마찰 오터치 방어
  - **Verification Gate 5:**
    - [x] Compose UI 렌더링 검증 및 `compile_applet` 통과 (PASS)
    - [x] 볼륨 키 이벤트 인터셉트 및 3초 롱프레스 제스처 바인딩 검증 (PASS)

- [x] **Milestone 6: 전체 파이프라인 통합, 자가 코드 리뷰 및 실기기 캘리브레이션 가이드 작성**
  - [x] 6.1 전체 컴포넌트(`BluetoothHidMouseManager`, `SensorFusionController`, `MainActivity`, Compose UI) 연결
  - [x] 6.2 자가 코드 리뷰 (리소스 누수, 비동기 스레드 안전성, HID 디스크립터 정밀도 점검)
  - [x] 6.3 `TESTING_GUIDE.md` 작성 (실제 스마트폰 책상 밀착 시 스마트폰 케이스 높이차 극복, 조명 캘리브레이션, iPadOS/Android 태블릿 블루투스 마우스 페어링 절차)
  - **Verification Gate 6:**
    - [x] 최종 `compile_applet` 빌드 100% 성공 (PASS)
    - [x] `gradle assembleDebug` 0개 에러 빌드 완료 (PASS)
    - [x] 전체 단위 테스트 통과 (PASS)
    - [x] 마일스톤 체크리스트 전체 완료 확인 (PASS)
