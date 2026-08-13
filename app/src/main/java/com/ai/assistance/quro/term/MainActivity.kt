package com.ai.assistance.quro.term

import ai.aidl.aci.core.AidlAciRequest
import ai.aidl.aci.core.IAidlAciService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as CColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
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
 *  - 终端：基于 Termux terminal-view 的**真·PTY 终端**（bash/sh、ANSI 颜色、交互式程序、cd/env 跨命令保留），对标 Termux。
 *  - 操控台：自绑定 ACI Service（同进程 AIDL），可视化能力列表与手动调 call()。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 让内容延伸到系统栏（刘海/状态栏），由 Compose 自行处理安全区，避免工具条压到摄像头。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        ShellEngine.init(applicationContext)
        installCrashLogger(applicationContext)
        setContent { TermApp() }
    }
}

// ── 崩溃落盘（无需 adb，用户用文件管理器即可取到 Download/QuroAI_logs/） ──
fun installCrashLogger(ctx: Context) {
    val def = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        try { logCrash(ctx, "uncaught@${t.name}", e) } catch (_: Throwable) {}
        def?.uncaughtException(t, e)
    }
}

fun logCrash(ctx: Context, where: String, e: Throwable) {
    try {
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "QuroAI_logs")
        dir.mkdirs()
        val f = File(dir, "termaci_crash_${System.currentTimeMillis()}.txt")
        f.appendText("=== TermAci crash @ ${Date()} ===\nwhere: $where\n${Log.getStackTraceString(e)}\n\n")
    } catch (_: Throwable) {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermApp() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
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

// ───────────────────────── 终端 Tab（真·PTY） ─────────────────────────

/**
 * 整合 Termux terminal-view 的客户端回调（同时实现 Session 与 View 两个接口）。
 * 渲染由 TerminalView 自行完成，这里只做必要的无操作回调 + 日志。
 */
private class TermuxClient : TerminalSessionClient, TerminalViewClient {
    // ── TerminalSessionClient ──
    override fun onTextChanged(changedSession: TerminalSession) {}
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
    override fun onPasteTextFromClipboard(session: TerminalSession) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null

    // ── TerminalViewClient ──
    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(e: android.view.MotionEvent) {}
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: android.view.MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {}

    // ── 日志 ──
    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "", e) }
}

@Composable
fun TerminalScreen(context: Context) {
    val client = remember { TermuxClient() }
    val terminalViewState = remember { mutableStateOf<TerminalView?>(null) }
    val showIme = remember { mutableStateOf(true) }
    val termError = remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        // 顶部工具条（已处于安全区之内）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Zorv 终端 · 真 PTY", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            if (termError.value != null) {
                TextButton(onClick = { termError.value = null }) { Text("重试") }
            }
            TextButton(onClick = {
                val tv = terminalViewState.value ?: return@TextButton
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                if (showIme.value) {
                    tv.requestFocus()
                    imm.showSoftInput(tv, InputMethodManager.SHOW_IMPLICIT)
                } else {
                    imm.hideSoftInputFromWindow(tv.windowToken, 0)
                }
                showIme.value = !showIme.value
            }) {
                Text(if (showIme.value) "收起键盘" else "弹出键盘")
            }
        }
        // 真终端视图（全屏黑底，对标 Termux）
        Box(Modifier.fillMaxSize().weight(1f).background(CColor.Black)) {
            if (termError.value != null) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
                    Text("终端启动失败", color = CColor(0xFFEF5350), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(termError.value ?: "", color = CColor.White, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("日志在 Download/QuroAI_logs/termaci_crash_*.txt", color = CColor.Gray, fontSize = 11.sp)
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val view = TerminalView(ctx, null)
                        view.setTerminalViewClient(client)
                        view.isFocusable = true
                        view.isFocusableInTouchMode = true
                        view.setBackgroundColor(Color.BLACK)
                        // 关键：初始化渲染器（Termux 标准步骤）。漏掉会导致 onMeasure 时
                        // mRenderer 为 null 而 NPE 闪退。
                        view.setTextSize(14)

                        try {
                            // 环境：TERM / HOME / PATH / LANG，使 shell 体验接近 Termux
                            val env = mutableListOf<String>()
                            env.add("TERM=xterm-256color")
                            env.add("HOME=" + ctx.filesDir.absolutePath)
                            env.add("LANG=en_US.UTF-8")
                            val sysPath = System.getenv("PATH")
                            if (!sysPath.isNullOrBlank()) env.add("PATH=$sysPath")
                            // 优先 bash，回退 sh
                            val shell = if (Runtime.getRuntime().exec(arrayOf("sh", "-c", "command -v bash")).inputStream.bufferedReader().readLine()?.isNotBlank() == true) "bash" else "/system/bin/sh"

                            val session = TerminalSession(
                                shell,
                                ctx.filesDir.absolutePath,
                                arrayOf(),
                                env.toTypedArray(),
                                5000,
                                client
                            )
                            view.attachSession(session)
                            view.requestFocus()
                            terminalViewState.value = view
                        } catch (e: Throwable) {
                            logCrash(ctx, "TerminalSession init", e)
                            termError.value = "创建 PTY 会话失败：${e.message}"
                        }
                        view
                    },
                    onRelease = { view ->
                        try { view.currentSession?.finishIfRunning() } catch (_: Throwable) {}
                    }
                )
            }
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
                Text("● 服务：${if (bound.value) "已连接 (AIDL 同进程绑定)" else "未连接"}", fontSize = 13.sp, color = if (bound.value) CColor(0xFF2E7D32) else MaterialTheme.colorScheme.error)
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
            Text(resultText.value, Modifier.padding(12.dp).verticalScroll(rememberScrollState()), fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        Spacer(Modifier.height(16.dp))
        Text("调用日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
            Text(if (log.value.isBlank()) "（暂无）" else log.value, Modifier.padding(12.dp), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
    }
}
