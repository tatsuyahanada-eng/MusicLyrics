package com.tatsuya.idtool

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.Config
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARScene
import io.github.sceneview.rememberEngine
import kotlin.math.sqrt

private const val SLOT_COUNT = 10

class DistanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF26A69A))) {
                DistanceScreen()
            }
        }
    }
}

@Composable
private fun DistanceScreen() {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasCamera = it }

    LaunchedEffect(Unit) {
        if (!hasCamera) permLauncher.launch(Manifest.permission.CAMERA)
    }

    if (hasCamera) {
        MeasureContent()
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "距離測定にはカメラの許可が必要です。",
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("カメラを許可")
            }
        }
    }
}

@Composable
private fun MeasureContent() {
    val context = LocalContext.current

    // 計測スロット（メモ＋距離）
    val memos = remember { mutableStateListOf<String>().apply { repeat(SLOT_COUNT) { add("") } } }
    val dists = remember { mutableStateListOf<Float?>().apply { repeat(SLOT_COUNT) { add(null) } } }
    var selected by remember { mutableIntStateOf(0) }

    var tracking by remember { mutableStateOf(false) }
    var latest by remember { mutableStateOf<FloatArray?>(null) } // 現在位置 x,y,z
    var startPos by remember { mutableStateOf<FloatArray?>(null) } // 始点 x,y,z

    // 始点からの現在の直線距離（プレビュー）
    val livePreview: Float? = run {
        val s = startPos; val l = latest
        if (s != null && l != null) dist3(s, l) else null
    }

    val engine = rememberEngine()

    Box(modifier = Modifier.fillMaxSize()) {
        ARScene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            planeRenderer = false,
            sessionConfiguration = { _, config ->
                config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                config.lightEstimationMode = Config.LightEstimationMode.DISABLED
                config.depthMode = Config.DepthMode.DISABLED
            },
            onSessionUpdated = { _, frame ->
                val cam = frame.camera
                if (cam.trackingState == TrackingState.TRACKING) {
                    tracking = true
                    val p = cam.pose
                    latest = floatArrayOf(p.tx(), p.ty(), p.tz())
                } else {
                    tracking = false
                }
            }
        )

        // ── 操作オーバーレイ ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // ステータス
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Column {
                    Text(
                        if (tracking) "トラッキング中" else "周囲を映して動かしてください…",
                        color = if (tracking) Color(0xFF80CBC4) else Color(0xFFFFB74D),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        "対象: ${selected + 1} 番（${memos[selected].ifBlank { "メモ未記入" }}）",
                        color = Color.White, fontSize = 13.sp
                    )
                    Text(
                        when {
                            startPos == null -> "「始点をセット」を押してください"
                            livePreview != null -> "始点から現在: %.2f m".format(livePreview)
                            else -> "移動して「終点をセット」"
                        },
                        color = Color(0xFFE0E0E0), fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 始点・終点ボタン
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (tracking && latest != null) {
                            startPos = latest!!.copyOf()
                        } else {
                            Toast.makeText(context, "トラッキングが安定してから押してください", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (startPos == null) "始点をセット" else "始点を再設定") }

                Button(
                    onClick = {
                        val s = startPos; val l = latest
                        if (s != null && l != null && tracking) {
                            dists[selected] = dist3(s, l)
                            startPos = null
                            Toast.makeText(
                                context,
                                "%d番: %.2f m".format(selected + 1, dists[selected]),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(context, "先に始点をセットしてください", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = startPos != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF26A69A)),
                    modifier = Modifier.weight(1f)
                ) { Text("終点をセット（測定）") }
            }

            Spacer(Modifier.height(8.dp))

            // スロット一覧
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                    .padding(6.dp)
            ) {
                itemsIndexed(memos) { i, _ ->
                    SlotRow(
                        index = i,
                        memo = memos[i],
                        onMemoChange = { memos[i] = it },
                        meters = dists[i],
                        selected = selected == i,
                        onSelect = { selected = i },
                        onClear = { dists[i] = null }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // コピー / 共有
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { copyText(context, buildReport(memos, dists)) },
                    modifier = Modifier.weight(1f)
                ) { Text("コピー") }
                OutlinedButton(
                    onClick = { shareText(context, buildReport(memos, dists)) },
                    modifier = Modifier.weight(1f)
                ) { Text("共有（保存）") }
            }
        }
    }
}

@Composable
private fun SlotRow(
    index: Int,
    memo: String,
    onMemoChange: (String) -> Unit,
    meters: Float?,
    selected: Boolean,
    onSelect: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(
                if (selected) Color(0x3326A69A) else Color.Transparent,
                RoundedCornerShape(6.dp)
            )
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onSelect,
            modifier = Modifier.width(44.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) { Text("${index + 1}") }

        OutlinedTextField(
            value = memo,
            onValueChange = onMemoChange,
            placeholder = { Text("メモ", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp)
        )

        Text(
            text = meters?.let { "%.2f m".format(it) } ?: "—",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.width(64.dp)
        )
    }
}

private fun dist3(a: FloatArray, b: FloatArray): Float {
    val dx = a[0] - b[0]
    val dy = a[1] - b[1]
    val dz = a[2] - b[2]
    return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun buildReport(memos: List<String>, dists: List<Float?>): String {
    val sb = StringBuilder("距離測定結果\n")
    for (i in memos.indices) {
        val label = memos[i].ifBlank { "(未記入)" }
        val d = dists[i]?.let { "%.2f m".format(it) } ?: "-"
        sb.append("${i + 1}. $label: $d\n")
    }
    return sb.toString().trimEnd()
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("距離測定", text))
    Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "距離測定結果を保存・共有"))
}
