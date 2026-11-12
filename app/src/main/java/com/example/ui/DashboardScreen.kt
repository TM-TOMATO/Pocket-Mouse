package com.example.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.DeskMouseUiState
import com.example.R
import com.example.fusion.FusionTelemetry
import com.example.hid.HidConnectionState
import com.example.hid.HidMetrics
import com.example.sensor.ImuMotionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    uiState: DeskMouseUiState,
    hidState: HidConnectionState,
    hidMetrics: HidMetrics,
    imuState: ImuMotionState,
    telemetry: FusionTelemetry,
    onRequestPermissions: () -> Unit,
    onStartSurfaceMode: () -> Unit,
    onDpiChange: (Float) -> Unit,
    onKRotChange: (Float) -> Unit,
    onInvertXChange: (Boolean) -> Unit,
    onInvertYChange: (Boolean) -> Unit,
    onScrollModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mouse,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Visual-Inertial Desk Mouse",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

                    // 1. Permissions Banner if missing
                    if (!uiState.hasCameraPermission || !uiState.hasBluetoothPermission) {
                        OutlinedCard(
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("permission_banner")
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "권한 승인이 필요합니다",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                Text(
                                    text = "바닥 광학 추적을 위한 카메라와 태블릿 페어링을 위한 블루투스 권한이 필요합니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                                Button(
                                    onClick = onRequestPermissions,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier.fillMaxWidth().testTag("grant_permission_button")
                                ) {
                                    Text("필수 권한 승인하기")
                                }
                            }
                        }
                    }

                    // 2. Bluetooth HID Connection Status Card
                    BluetoothStatusCard(hidState = hidState, hidMetrics = hidMetrics)

                    // 3. Live Optical Flow & CLAHE Preview Card
                    OpticalFlowPreviewCard(
                        telemetry = telemetry,
                        imuState = imuState,
                        debugBitmap = telemetry.debugBitmap
                    )

                    // 4. Calibration & Tuning Controls Card
                    CalibrationCard(
                        dpiScale = uiState.dpiScale,
                        kRot = uiState.kRot,
                        invertX = uiState.invertX,
                        invertY = uiState.invertY,
                        isScrollMode = uiState.isScrollMode,
                        onDpiChange = onDpiChange,
                        onKRotChange = onKRotChange,
                        onInvertXChange = onInvertXChange,
                        onInvertYChange = onInvertYChange,
                        onScrollModeChange = onScrollModeChange
                    )

                    // 5. Start Surface Mode Primary Action
                    Button(
                        onClick = onStartSurfaceMode,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("start_surface_mode_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "바닥 주행 모드 시작 (Start Mouse)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
fun BluetoothStatusCard(
    hidState: HidConnectionState,
    hidMetrics: HidMetrics
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("hid_status_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (hidMetrics.isHostConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = if (hidMetrics.isHostConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Bluetooth HID 마우스",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                val (badgeText, badgeColor) = when (hidState) {
                    is HidConnectionState.Connected -> Pair("연결됨", Color(0xFF4CAF50))
                    is HidConnectionState.Connecting -> Pair("연결 중", Color(0xFFFFA000))
                    is HidConnectionState.RegisteredNotConnected -> Pair("대기 중", Color(0xFF2196F3))
                    is HidConnectionState.ServiceConnecting -> Pair("서비스 시작", Color(0xFF9E9E9E))
                    is HidConnectionState.BluetoothDisabled -> Pair("BT 꺼짐", Color(0xFFE53935))
                    is HidConnectionState.PermissionRequired -> Pair("권한 필요", Color(0xFFE53935))
                    is HidConnectionState.Error -> Pair("오류", Color(0xFFE53935))
                    else -> Pair("미연결", Color(0xFF757575))
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (hidMetrics.isHostConnected) {
                Text(
                    text = "연결 기기: ${hidMetrics.hostDeviceName}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Text(
                    text = "태블릿의 Bluetooth 설정에서 'DeskMouse Optical'을 페어링하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("전송률", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${hidMetrics.sendRateHz.toInt()} Hz", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("누적 패킷", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${hidMetrics.packetsSent}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("마지막 패킷", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "dX:${hidMetrics.lastPacket.deltaX} dY:${hidMetrics.lastPacket.deltaY}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun OpticalFlowPreviewCard(
    telemetry: FusionTelemetry,
    imuState: ImuMotionState,
    debugBitmap: Bitmap?
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("optical_flow_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "VIO 광학 흐름 & 센서 융합",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "${telemetry.fps.toInt()} FPS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // CLAHE & Optical Flow Live Canvas / Placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (debugBitmap != null) {
                    Image(
                        bitmap = debugBitmap.asImageBitmap(),
                        contentDescription = "Live CLAHE Optical Flow Feed",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("전면 카메라 프리뷰 대기 중...", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }

                // Overlay status badge on top-left of preview
                val (statusText, statusBg) = when {
                    telemetry.isLiftedOff -> Pair("LIFT-OFF (음소거)", Color(0xFFFFA000))
                    telemetry.isLowFeature -> Pair("특징점 부족 (보간)", Color(0xFFE53935))
                    telemetry.isScrollMode -> Pair("스크롤 모드", Color(0xFF2196F3))
                    else -> Pair("정상 추적 (TRACKING)", Color(0xFF4CAF50))
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(statusBg.copy(alpha = 0.85f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(text = statusText, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Feature Confidence Progress
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "특징점 신뢰도 (${(telemetry.featureConfidence * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "유효 특징점: ${telemetry.validFeatures}개",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { telemetry.featureConfidence },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (telemetry.featureConfidence > 0.5f) Color(0xFF4CAF50) else Color(0xFFFFA000)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Telemetry Vectors Data Table
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("원시 광학", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${telemetry.rawOptX.toInt()}, ${telemetry.rawOptY.toInt()}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("자이로 보정", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("-${telemetry.gyroCompX.toInt()}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Color(0xFF2196F3))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("최종 출력", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${telemetry.finalDx}, ${telemetry.finalDy}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun CalibrationCard(
    dpiScale: Float,
    kRot: Float,
    invertX: Boolean,
    invertY: Boolean,
    isScrollMode: Boolean,
    onDpiChange: (Float) -> Unit,
    onKRotChange: (Float) -> Unit,
    onInvertXChange: (Boolean) -> Unit,
    onInvertYChange: (Boolean) -> Unit,
    onScrollModeChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("calibration_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "마우스 감도 및 캘리브레이션",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // DPI Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("커서 이동 감도 (DPI)", style = MaterialTheme.typography.bodyMedium)
                Text("${String.format("%.1f", dpiScale)}x", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = dpiScale,
                onValueChange = onDpiChange,
                valueRange = 0.5f..5.0f,
                steps = 44,
                modifier = Modifier.testTag("dpi_slider")
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Gyro Rotational Compensation k_rot Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("자이로 회전 상쇄 계수 (k_rot)", style = MaterialTheme.typography.bodyMedium)
                Text("${String.format("%.1f", kRot)}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = kRot,
                onValueChange = onKRotChange,
                valueRange = 0.0f..3.0f,
                steps = 29,
                modifier = Modifier.testTag("krot_slider")
            )
            Text(
                text = "손목을 축으로 폰을 회전시킬 때 발생하는 가짜 평행이동을 상쇄합니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(10.dp))

            // Invert switches
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("X축 방향 반전", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = invertX, onCheckedChange = onInvertXChange, modifier = Modifier.testTag("invert_x_switch"))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Y축 방향 반전 (자연스러운 스크롤/태블릿)", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = invertY, onCheckedChange = onInvertYChange, modifier = Modifier.testTag("invert_y_switch"))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("스크롤 모드 수동 토글 (볼륨 상+하 단축키)", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = isScrollMode, onCheckedChange = onScrollModeChange, modifier = Modifier.testTag("scroll_mode_switch"))
            }
        }
    }
}
