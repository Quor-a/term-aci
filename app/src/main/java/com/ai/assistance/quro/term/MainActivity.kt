package com.ai.assistance.quro.term

import ai.aidl.aci.core.AidlAciRequest
import ai.aidl.aci.core.IAidlAciService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 终端 App 主界面（Jetpack Compose）。既可作为 Zorv AI 的 ACI 受控端（本地 shell），也可独立使用。
 *
 * 两个 Tab：
 *  - 终端：交互式 shell（命令输入 / 输出日志 / cwd 显示 / 历史 ↑↓），对标 Termux 的本地终端。
 *  - 操控台：自绑定 ACI Service（同进程 AIDL），可视化能力列表与手动调 call()。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShellEngine.init(applicationContext)
        setContent { TermApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermApp() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("终端") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("操控台") })
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        0 -> TerminalScreen(context)
                        1 -> ConsoleScreen(context)
                    }
                }
            }
        }
    }
}

// ───────────────────────── 终端 Tab ─────────────────────────

private enum class LineKind { INPUT, OUTPUT, SYSTEM }

private data class TermLine(val text: String, val kind: LineKind)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(context: Context) {
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateOf<List<TermLine>>(listOf(TermLine("TermAci 本地终端已就绪 · 输入命令后回车执行（cd / clear / pwd 为内置）", LineKind.SYSTEM)) ) }
    val input = remember { mutableStateOf(TextFieldValue("")) }
    val history = remember { mutableStateOf<List<String>>(emptyList()) }
    val histIdx = remember { mutableStateOf(-1) }
    val busy = remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun append(line: TermLine) { lines.value = lines.value + line }

    fun runCommand(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return
        history.value = history.value + cmd
        histIdx.value = history.value.size
        append(TermLine("❯ $cmd", LineKind.INPUT))
        when {
            cmd == "clear" -> lines.value = emptyList()
            cmd == "pwd" -> append(TermLine(ShellEngine.getCwd(), LineKind.OUTPUT))
            cmd.startsWith("cd") -> {
                val target = cmd.removePrefix("cd").trim()
                val base = ShellEngine.getCwd()
                val newDir = if (target.isEmpty()) base else try { File(base, target).canonicalPath } catch (_: Throwable) { base }
                val ok = ShellEngine.setCwd(newDir)
                append(TermLine(if (ok) "→ $newDir" else "cd: 无法访问 $newDir", LineKind.SYSTEM))
            }
            else -> {
                busy.value = true
                scope.launch(Dispatchers.IO) {
                    val r = ShellEngine.exec(cmd)
                    val out = if (r.stdout.isEmpty()) "(无输出)" else r.stdout
                    withContext(Dispatchers.Main) {
                        out.split("\n").forEach { append(TermLine(it, LineKind.OUTPUT)) }
                        if (r.timedOut) append(TermLine("[TermAci] 命令超时已终止", LineKind.SYSTEM))
                        append(TermLine("[exit ${if (r.timedOut) "TIMEOUT" else r.exitCode}]", LineKind.SYSTEM))
                        busy.value = false
                    }
                }
            }
        }
    }

    // 自动滚到底部
    LaunchedEffect(lines.value.size) {
        if (lines.value.isNotEmpty()) listState.scrollToItem(lines.value.size - 1)
    }

    val promptColor = MaterialTheme.colorScheme.primary
    val outColor = MaterialTheme.colorScheme.onSurface
    val sysColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        // 状态条：cwd + 快捷
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📂 ${ShellEngine.getCwd()}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { runCommand("pwd") }) { Text("pwd") }
            TextButton(onClick = { runCommand("ls -la") }) { Text("ls") }
            TextButton(onClick = { runCommand("clear") }) { Text("clear") }
        }
        Spacer(Modifier.height(6.dp))
        // 输出区
        LazyColumn(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).padding(8.dp), state = listState) {
            items(lines.value) { l ->
                val color = when (l.kind) { LineKind.INPUT -> promptColor; LineKind.SYSTEM -> sysColor; else -> outColor }
                val weight = if (l.kind == LineKind.INPUT) FontWeight.Bold else FontWeight.Normal
                Text(l.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = color, fontWeight = weight)
            }
        }
        Spacer(Modifier.height(6.dp))
        // 输入区
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("❯", fontSize = 15.sp, fontFamily = FontFamily.Monospace, color = promptColor, modifier = Modifier.padding(end = 6.dp))
            OutlinedTextField(
                value = input.value,
                onValueChange = { input.value = it },
                modifier = Modifier.weight(1f)                .onPreviewKeyEvent { ev ->
                    if (ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && ev.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                        val h = history.value
                        if (h.isNotEmpty()) {
                            histIdx.value = (histIdx.value - 1).coerceAtLeast(0)
                            input.value = TextFieldValue(h[histIdx.value], TextRange(h[histIdx.value].length))
                        }
                        true
                    } else if (ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && ev.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                        val h = history.value
                        if (histIdx.value in 0 until h.size) {
                            histIdx.value = (histIdx.value + 1).coerceAtMost(h.size)
                            input.value = if (histIdx.value == h.size) TextFieldValue("") else TextFieldValue(h[histIdx.value], TextRange(h[histIdx.value].length))
                        }
                        true
                    } else false
                },
                placeholder = { Text("输入 shell 命令…（↑↓ 翻历史）") },
                singleLine = true,
                enabled = !busy.value
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = { val c = input.value.text; input.value = TextFieldValue(""); runCommand(c) }, enabled = !busy.value) { Text("执行") }
        }
    }
}

// ───────────────────────── 操控台 Tab ─────────────────────────

private data class CapInfo(val id: String, val description: String)

private val EXAMPLE_PARAMS = mapOf(
    "term_exec" to """{"cmd":"echo hello && pwd"}""",
    "term_exec_bg" to """{"cmd":"sleep 5 && echo done"}""",
    "term_jobs" to "{}",
    "term_job_output" to """{"jobId":"<后台任务id>"}""",
    "term_kill" to """{"jobId":"<后台任务id>"}""",
    "term_read_file" to """{"path":"/proc/version"}""",
    "term_write_file" to """{"path":"<绝对路径>","content":"hello"}""",
    "term_list_dir" to """{"path":""}""",
    "term_status" to "{}"
)

private fun parseCap(json: String): CapInfo? = try {
    val o = JSONObject(json)
    CapInfo(o.optString("id", ""), o.optString("description", ""))
} catch (_: Throwable) { null }

private fun buildBundle(json: String): android.os.Bundle {
    val b = android.os.Bundle()
    if (json.isBlank()) return b
    val o = JSONObject(json)
    val it = o.keys()
    while (it.hasNext()) {
        val k = it.next()
        when (val v = o.get(k)) {
            is Boolean -> b.putBoolean(k, v)
            is Int -> b.putInt(k, v)
            is Long -> b.putLong(k, v)
            is Double -> b.putDouble(k, v)
            is String -> b.putString(k, v)
            else -> b.putString(k, v.toString())
        }
    }
    return b
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(context: Context) {
    val scope = rememberCoroutineScope()
    val serviceProxy = remember { mutableStateOf<IAidlAciService?>(null) }
    val bound = remember { mutableStateOf(false) }
    val caps = remember { mutableStateOf<List<CapInfo>>(emptyList()) }
    val selectedCap = remember { mutableStateOf("") }
    val paramsText = remember { mutableStateOf("{}") }
    val resultText = remember { mutableStateOf("（选择一个能力并点击「执行调用」）") }
    val log = remember { mutableStateOf("") }
    val invoking = remember { mutableStateOf(false) }
    val expanded = remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val proxy = IAidlAciService.Stub.asInterface(binder)
                serviceProxy.value = proxy
                bound.value = true
                try {
                    val arr = proxy?.getCapabilities() ?: emptyArray()
                    caps.value = arr.mapNotNull { parseCap(it) }
                    if (selectedCap.value.isBlank() && caps.value.isNotEmpty()) selectedCap.value = caps.value.first().id
                } catch (_: Throwable) { }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                serviceProxy.value = null
                bound.value = false
            }
        }
        val intent = Intent("ai.aci.core.ACTION_BIND").setPackage(context.packageName)
        context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        onDispose { try { context.unbindService(conn) } catch (_: Throwable) { } }
    }

    fun invokeCapability() {
        val proxy = serviceProxy.value ?: return
        val cap = selectedCap.value
        if (cap.isBlank()) return
        invoking.value = true
        resultText.value = "调用中…"
        scope.launch(Dispatchers.IO) {
            try {
                val request = AidlAciRequest(cap).apply {
                    params = buildBundle(paramsText.value)
                    callerPkg = context.packageName
                }
                val resp = proxy.call(request)
                val ok = resp.isSuccess
                val sb = StringBuilder()
                sb.append(if (ok) "✅ 成功" else "❌ 失败 (code=${resp.errorCode}): ${resp.errorMessage}\n")
                resp.result?.let { r ->
                    for (key in r.keySet()) {
                        val v = r.get(key)
                        val s = if (v is String && v.length > 600) v.take(600) + "\n…(已截断)" else (v?.toString() ?: "null")
                        sb.append("• $key = $s\n")
                    }
                }
                val text = sb.toString()
                val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                withContext(Dispatchers.Main) {
                    resultText.value = text
                    log.value = "[$ts] $cap → ${if (ok) "OK" else "FAIL"}\n" + log.value
                    invoking.value = false
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    resultText.value = "调用异常：${e.message}"
                    invoking.value = false
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("ACI 受控端状态", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("● 服务：${if (bound.value) "已连接 (AIDL 同进程绑定)" else "未连接"}", fontSize = 13.sp, color = if (bound.value) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
                Text("● 双通道：AIDL + LocalSocket（抽象命名空间，端点=包名）", fontSize = 13.sp)
                Text("● 包名：${context.packageName}", fontSize = 13.sp)
                Text("● 已注册能力数：${caps.value.size}", fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("能力列表", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        caps.value.forEach { c ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(c.id, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    Text(c.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (caps.value.isEmpty()) Text("（等待服务连接…）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text("手动调用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        ExposedDropdownMenuBox(expanded = expanded.value, onExpandedChange = { expanded.value = it }) {
            TextField(
                readOnly = true, value = selectedCap.value, onValueChange = {}, label = { Text("选择能力") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded.value) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
                caps.value.forEach { c ->
                    DropdownMenuItem(text = { Text(c.id) }, onClick = { selectedCap.value = c.id; paramsText.value = EXAMPLE_PARAMS[c.id] ?: "{}"; expanded.value = false })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = paramsText.value, onValueChange = { paramsText.value = it }, label = { Text("参数 JSON") }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp), singleLine = false)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { invokeCapability() }, enabled = bound.value && !invoking.value, modifier = Modifier.fillMaxWidth()) { Text(if (invoking.value) "调用中…" else "执行调用") }
        Spacer(Modifier.height(16.dp))
        Text("返回结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
            Text(resultText.value, Modifier.padding(12.dp).verticalScroll(rememberScrollState()), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(16.dp))
        Text("调用日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
            Text(if (log.value.isBlank()) "（暂无）" else log.value, Modifier.padding(12.dp), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ── 工具 ──────────────────────────────────────────────────

private fun fmtTime(ms: Long): String {
    if (ms <= 0L) return "--"
    return try { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms)) } catch (_: Throwable) { "--" }
}
