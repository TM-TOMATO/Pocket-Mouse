package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.DeskMouseUiState
import com.example.fusion.FusionTelemetry
import com.example.hid.HidConnectionState
import com.example.hid.HidMetrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Surface Driving Mode:
 * - Top >= 38% height is pure solid white (#FFFFFF) for high-luminance ground illumination
 * - Center displays high-contrast real-time sensor status (Active / Lift-off / Low-texture / Scroll)
 * - Volume button click feedback
 * - Bottom incorporates a 3-second Long-Press progress hold button to prevent accidental exits from desk friction.
 */
@Composable
fun SurfaceDrivingScreen(
    uiState: DeskMouseUiState,
    hidState: HidConnectionState,
    hidMetrics: HidMetrics,
    telemetry: FusionTelemetry,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 3-second long press progress state
    var isHolding by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isHolding) {
        if (isHolding) {
            val totalHoldTimeMs = 3000L
            val stepIntervalMs = 25L
            var elapsedMs = 0L

            while (isActive && isHolding && elapsedMs < totalHoldTimeMs) {
                delay(stepIntervalMs)
                elapsedMs += stepIntervalMs
                holdProgress = (elapsedMs.toFloat() / totalHoldTimeMs).coerceIn(0f, 1f)
            }

            if (isHolding && elapsedMs >= totalHoldTimeMs) {
                onExit()
            }
        } else {
            holdProgress = 0f
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101216))
    ) {
        // 1. High-Luminance Ground Illumination Bar (Top >= 38% height)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.38f)
                .background(Color.White)
                .testTag("optical_illumination_layer"),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "광학 바닥 조명 (High-Luminance Illumination)",
                    color = Color(0xFF666666),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "전면 카메라를 바닥면으로 밀착해 마우스로 사용하세요",
                    color = Color(0xFF888888),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // 2. Middle Driving Status and Telemetry (High-Contrast Black/Dark Background)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            // Top Status Badge
            val (statusText, statusDesc, statusColor) = when {
                telemetry.isLiftedOff -> Triple(
                    "LIFT-OFF (음소거)",
                    "마우스가 바닥에서 들어올려져 이동이 일시 중지되었습니다.",
                    Color(0xFFFFA000)
                )
                telemetry.isLowFeature -> Triple(
                    "특징점 부족 (보간 유지)",
                    "바닥면 질감이 부족합니다. 마우스패드 사용을 권장합니다.",
                    Color(0xFFE53935)
                )
                uiState.isScrollMode -> Triple(
                    "스크롤 모드 활성",
                    "위아래 이동이 마우스 휠 스크롤로 전송됩니다.",
                    Color(0xFF2196F3)
                )
                else -> Triple(
                    "ACTIVE TRACKING (정상 주행)",
                    "바닥 광학 흐름 및 자이로 융합 추적 중입니다.",
                    Color(0xFF4CAF50)
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E222B))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        color = statusColor,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = statusDesc,
                    color = Color(0xFFAAAAAA),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            // Real-Time Mouse Transmission Stats
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF181B22))
                    .padding(vertical = 12.dp, horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("주파수", color = Color(0xFF888888), style = MaterialTheme.typography.labelSmall)
                    Text("${hidMetrics.sendRateHz.toInt()} Hz", color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("누적 패킷", color = Color(0xFF888888), style = MaterialTheme.typography.labelSmall)
                    Text("${hidMetrics.packetsSent}", color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("이동량 (X, Y)", color = Color(0xFF888888), style = MaterialTheme.typography.labelSmall)
                    Text("${telemetry.finalDx}, ${telemetry.finalDy}", color = Color(0xFF4CAF50), style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }

            // Physical Volume Button Interactive Indicators
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF181B22))
                    .padding(12.dp)
            ) {
                Text(
                    text = "물리 버튼 클릭 피드백",
                    color = Color(0xFF888888),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Left Click (Vol UP)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (uiState.isVolUpPressed) Color(0xFF2196F3) else Color(0xFF252A36)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (uiState.isVolUpPressed) "좌클릭 (PRESS)" else "볼륨 [상]: 좌클릭",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Right Click (Vol DOWN)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (uiState.isVolDownPressed) Color(0xFF9C27B0) else Color(0xFF252A36)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (uiState.isVolDownPressed) "우클릭 (PRESS)" else "볼륨 [하]: 우클릭",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (uiState.isScrollMode) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1565C0)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("스크롤 모드 활성 (상+하 동시 입력으로 해제)", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // 3. Bottom 3-Second Long Press Progress Exit Guardrail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xFF252A36))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isHolding = true
                                val released = tryAwaitRelease()
                                isHolding = false
                            }
                        )
                    }
                    .testTag("hold_to_exit_button"),
                contentAlignment = Alignment.Center
            ) {
                // Progress Bar Background Fill
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(holdProgress)
                        .align(Alignment.CenterStart)
                        .background(Color(0xFFE53935).copy(alpha = 0.8f))
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isHolding) {
                        CircularProgressIndicator(
                            progress = { holdProgress },
                            color = Color.White,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "종료 중... (${((1f - holdProgress) * 3f + 0.1f).toInt()}초 유지)",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = Color(0xFFAAAAAA))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "길게 3초간 눌러 주행 모드 종료 (Hold 3s)",
                            color = Color(0xFFAAAAAA),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
