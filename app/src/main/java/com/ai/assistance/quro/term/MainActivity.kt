package com.ai.assistance.quro.term

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 终端 App 主界面（Jetpack Compose）。既可作为 Zorv AI 的 ACI 受控端（本地 shell），也可独立使用。
 *
 * 基于 Termux terminal-view 的**真·PTY 终端**（bash/sh、ANSI 颜色、交互式程序、cd/env 跨命令保留），对标 Termux。
 *
 * 注意：控制台（操控台）是**控制端**功能，本 App 不再内置调试台；由控制端经 ACI 能力
 * console_ui / console_action 拉取 TermAciConsoleBackend 的 SDUI 快照并由 AciConsoleScreen 渲染。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 让内容延伸到系统栏（刘海/状态栏），由 Compose 用 safeDrawing 自行处理安全区，避免工具条压到摄像头。
        enableEdgeToEdge()
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
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    TerminalScreen(context)
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

