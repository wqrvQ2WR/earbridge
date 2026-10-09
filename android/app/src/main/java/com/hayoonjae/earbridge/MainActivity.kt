package com.hayoonjae.earbridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.log10

// 사용자 취향: 밝고 채도 높은 그라디언트 포인트. 이모지 금지.
val Brand = Brush.linearGradient(listOf(Color(0xFFFF4D9D), Color(0xFF9B5CFF), Color(0xFF3DA9FF)))

val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

private val Scheme = darkColorScheme(
    primary = Color(0xFFFF4D9D),
    secondary = Color(0xFF9B5CFF),
    tertiary = Color(0xFF3DA9FF),
    background = Color(0xFF0F0F17),
    surface = Color(0xFF181824),
    onBackground = Color(0xFFF2F2F7),
    onSurface = Color(0xFFF2F2F7),
)

private val Dim = Color(0xFF8E8EA0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = Scheme) { App() }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val st by MicService.state.collectAsStateWithLifecycle()
    var source by remember { mutableStateOf(MicSource.RAW) }
    val prefs = remember { ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var remote by remember { mutableStateOf(prefs.getBoolean("remote", true)) }
    var code by remember { mutableStateOf(RemoteSender.code(ctx)) }
    val power = remember { ctx.getSystemService(android.os.PowerManager::class.java) }
    var batteryFree by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(ctx.packageName)) }
    LifecycleResumeEffect(Unit) {
        batteryFree = power.isIgnoringBatteryOptimizations(ctx.packageName)
        onPauseOrDispose {}
    }
    var denied by remember { mutableStateOf(false) }

    val perms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) {
            denied = false
            MicService.start(ctx, source, remote)
        } else denied = true
    }

    fun toggle() {
        if (st.running) { MicService.stop(ctx); return }
        val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (ok) MicService.start(ctx, source, remote) else ask.launch(perms)
    }

    val base = TextStyle(fontFamily = Pretendard, color = MaterialTheme.colorScheme.onBackground)

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("EarBridge", style = base.copy(brush = Brand, fontSize = 34.sp, fontWeight = FontWeight.Bold))
        Text("폰 마이크 소리를 맥으로 보냅니다.", style = base.copy(color = Dim, fontSize = 15.sp))

        Spacer(Modifier.weight(1f))

        // 큰 시작/정지 버튼
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(180.dp)
                    .clip(CircleShape)
                    .then(
                        if (st.running) Modifier.background(Brand)
                        else Modifier.background(MaterialTheme.colorScheme.surface).border(3.dp, Brand, CircleShape)
                    )
                    .clickable { toggle() },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (st.running) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(64.dp),
                    )
                    Text(if (st.running) "보내는 중" else "시작", style = base.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold))
                }
            }
        }

        LevelBar(if (st.running) st.level else 0f)

        Spacer(Modifier.weight(1f))

        // 마이크 종류
        Text("마이크", style = base.copy(color = Dim, fontSize = 13.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MicSource.entries.forEach { s ->
                val sel = s == source
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .then(if (sel) Modifier.background(Brand) else Modifier.background(MaterialTheme.colorScheme.surface))
                        .clickable(enabled = !st.running) { source = s }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(s.label, style = base.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (sel || !st.running) Color.White else Dim))
                }
            }
        }

        // 다른 네트워크(파이 중계)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
                .clickable(enabled = !st.running) {
                    remote = !remote
                    prefs.edit().putBoolean("remote", remote).apply()
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("다른 네트워크에서도 듣기", style = base.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold))
                if (remote) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(code, style = base.copy(brush = Brand, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp))
                        if (!st.running) Icon(
                            Icons.Rounded.Refresh, contentDescription = "새 코드", tint = Dim,
                            modifier = Modifier.size(22.dp).clickable {
                                code = RemoteSender.newCode()
                                prefs.edit().putString("code", code).apply()
                            },
                        )
                    }
                    Text("맥 앱에 이 코드를 넣으면 어디서든 들을 수 있어요.", style = base.copy(color = Dim, fontSize = 12.sp))
                } else {
                    Text("꺼 두면 같은 와이파이에서만 들립니다.", style = base.copy(color = Dim, fontSize = 12.sp))
                }
            }
            Toggle(remote)
        }

        // 화면 꺼져도 계속 보내려면 배터리 최적화에서 빼야 삼성이 안 죽인다
        if (!batteryFree) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.5.dp, Brand, RoundedCornerShape(16.dp))
                    .clickable {
                        @SuppressLint("BatteryLife")
                        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                        ctx.startActivity(i)
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.BatteryAlert, contentDescription = null, tint = Color(0xFFFF4D9D))
                Column(Modifier.weight(1f)) {
                    Text("화면 꺼져도 계속 보내기", style = base.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold))
                    Text("눌러서 배터리 최적화를 꺼 주세요.", style = base.copy(color = Dim, fontSize = 12.sp))
                }
            }
        }

        // 상태 카드
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            InfoRow("주소", st.address?.let { "$it:${MicService.PORT}" } ?: "-", base)
            InfoRow("같은 와이파이", if (st.running) "${st.clients}대" else "-", base)
            if (st.remoteOn) InfoRow(
                "원격",
                when {
                    !st.running -> "-"
                    !st.remoteConnected -> "중계 서버 연결 중"
                    else -> "${st.remoteListeners}대"
                },
                base,
            )
            val msg = when {
                denied -> "마이크 권한이 있어야 보낼 수 있어요."
                st.error != null -> st.error
                st.running && st.clients == 0 && st.remoteListeners == 0 -> "맥에서 EarBridge를 열면 자동으로 찾아 붙습니다."
                else -> null
            }
            if (msg != null) Text(msg, style = base.copy(color = if (denied || st.error != null) Color(0xFFFF6B8B) else Dim, fontSize = 13.sp))
        }
    }
}

@Composable
private fun Toggle(on: Boolean) {
    Box(
        Modifier.size(width = 50.dp, height = 30.dp).clip(CircleShape)
            .then(if (on) Modifier.background(Brand) else Modifier.background(Color(0xFF2A2A3A))),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.padding(4.dp).size(22.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun InfoRow(k: String, v: String, base: TextStyle) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, style = base.copy(color = Dim, fontSize = 15.sp), modifier = Modifier.weight(1f))
        Text(v, style = base.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun LevelBar(rms: Float) {
    // RMS를 -80dB~0dB 범위로 펴서 보여준다
    val db = if (rms <= 0f) -80f else (20 * log10(rms)).coerceIn(-80f, 0f)
    val target = (db + 80f) / 80f
    val v by animateFloatAsState(target, tween(80), label = "level")
    Box(
        Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surface)
    ) {
        Box(Modifier.fillMaxWidth(v).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Brand))
    }
}
