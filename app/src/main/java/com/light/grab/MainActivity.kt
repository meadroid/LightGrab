package com.qingdan.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingdan.app.ui.theme.QingDanTheme
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// ===== 数据持久化（重启不丢） =====
object StatsStore {
    private const val PREF = "qingdan_stats"
    fun save(ctx: Context, today: Int, total: Int, lastTime: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().apply {
            putString("json", JSONObject().apply {
                put("today", today); put("total", total); put("last", lastTime)
            }.toString())
            put("lastDate", SimpleDateFormat("yyyyMMdd").format(Date()))
        }.apply()
    }
    fun load(ctx: Context): Triple<Int, Int, String> {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val todayIsNew = p.getString("lastDate", "") != SimpleDateFormat("yyyyMMdd").format(Date())
        val o = JSONObject(p.getString("json", "{}"))
        return Triple(if (todayIsNew) 0 else o.optInt("today", 0), o.optInt("total", 0), o.optString("last", ""))
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { QingDanTheme { MainScreen() } }
    }
}

val ACCENT = Color(0xFF2E6E5E)      // 主色调：深青绿，全局统一
val BG = Color(0xFF121A17)
val CARD = Color(0xFF1C2622)
val TEXT_MAIN = Color(0xFFE8EDEB)
val TEXT_SUB = Color(0xFF8FA39C)

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val modes = listOf("影子", "狙击", "哨兵", "静默")
    var currentMode by remember { mutableStateOf(modes[0]) }
    var (today, total, lastTime) = remember { StatsStore.load(ctx).let { mutableStateOf(it) } }
    var showMenu by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    fun refresh() { StatsStore.load(ctx).let { today = it.first; total = it.second; lastTime = it.third } }

    Box(Modifier.fillMaxSize().background(BG)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),   // ← 全局对齐线：16dp
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(40.dp))

            // ── 顶部：轻单 + 📓 + ⋮ ──
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("轻单", color = TEXT_MAIN, fontSize = 17.sp, fontWeight = FontWeight.Bold) // 状态栏15sp，这里大一号
                Spacer(Modifier.weight(1f))
                Text("📓", fontSize = 20.sp, modifier = Modifier
                    .clickable { showLog = true }.padding(8.dp))
                Text("⋮", fontSize = 20.sp, color = TEXT_MAIN, modifier = Modifier
                    .clickable { showMenu = true }.padding(8.dp))
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    listOf("目标应用", "关键词", "哨兵延时", "狙击延时", "权限").forEach { item ->
                        DropdownMenuItem(text = { Text(item, color = TEXT_MAIN) }, onClick = {
                            showMenu = false
                            // TODO: 跳转对应设置页
                        })
                    }
                }
            }

            // ── 中间状态栏 ──
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(CARD)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${currentMode}模式已启动", color = ACCENT, fontSize = 15.sp)
                    Text("今日战绩：命中${today}单 ${if (lastTime.isNotBlank()) "· $lastTime" else ""}",
                        color = TEXT_MAIN, fontSize = 15.sp)
                    Text("总战绩：命中${total}单", color = TEXT_SUB, fontSize = 15.sp)
                }
            }

            // ── 四个胶囊 ──
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { m ->
                    val selected = m == currentMode
                    Box(
                        Modifier
                            .weight(1f)
                            .background(if (selected) ACCENT else CARD, RoundedCornerShape(20.dp))
                            .clickable { currentMode = m }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(m, color = if (selected) Color.White else TEXT_SUB, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
