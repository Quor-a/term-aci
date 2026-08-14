package com.ai.assistance.quro.term

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets as AndroidWindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Menu
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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
 * 基于 Termux terminal-view 的**真·PTY 终端**（bash/sh、ANSI 颜色、交互式程序、cd/env 跨命令保留）。
 *
 * 注意：控制台（操控台）是**控制端**功能，本 App 不再内置调试台；由控制端经 ACI 能力
 * console_ui / console_action 拉取 TermAciConsoleBackend 的 SDUI 快照并由 AciConsoleScreen 渲染。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 彻底禁用系统 ActionBar / Title，避免部分 ROM 在 NoActionBar theme 下仍显示「终端」标题栏。
        actionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
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

// ── 帮助文档 ─────────────────────────────────────────────────────────

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zorv 终端 使用说明") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HelpSection("环境变量") {
                    Text("HOME = 应用私有目录")
                    Text("TERM = xterm-256color")
                    Text("LANG = en_US.UTF-8")
                    Text("PATH = 继承系统 PATH")
                }
                Spacer(Modifier.height(12.dp))
                HelpSection("可用 Shell") {
                    Text("优先 bash；未找到 bash 时回退 /system/bin/sh。")
                    Text("cd、export、alias 等状态跨命令保留。")
                }
                Spacer(Modifier.height(12.dp))
                HelpSection("交互操作") {
                    Text("· 点击终端区域获取焦点")
                    Text("· 顶部把手展开/收起工具条")
                    Text("· 工具条可切换软键盘显示/隐藏")
                    Text("· 支持 vim、top、mc 等交互式程序")
                }
                Spacer(Modifier.height(12.dp))
                HelpSection("存储访问") {
                    Text("终端拥有 MANAGE_EXTERNAL_STORAGE 权限时可直接读写 /storage/emulated/0；")
                    Text("否则仅能在 HOME（应用私有目录）和 SAF 授权目录操作。")
                }
                Spacer(Modifier.height(12.dp))
                HelpSection("后台任务（ACI 能力）") {
                    Text("控制端可调 term_exec / term_exec_bg / term_jobs / term_job_output / term_kill。")
                }
                Spacer(Modifier.height(12.dp))
                HelpSection("故障排查") {
                    Text("崩溃日志自动写入 Download/QuroAI_logs/termaci_crash_*.txt")
                    Text("无需 adb，用手机文件管理器即可查看。")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun HelpSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Column(content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermApp() {
    val context = LocalContext.current
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = CColor.Black) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                TerminalScreen(context)
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
    var toolbarExpanded by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(CColor.Black)) {
        // 真终端视图（全屏黑底）
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
                    view.setTextSize(14)

                    try {
                        val env = mutableListOf<String>()
                        env.add("TERM=xterm-256color")
                        env.add("HOME=" + ctx.filesDir.absolutePath)
                        env.add("LANG=en_US.UTF-8")
                        val sysPath = System.getenv("PATH")
                        if (!sysPath.isNullOrBlank()) env.add("PATH=$sysPath")
                        val shell = if (Runtime.getRuntime().exec(arrayOf("sh", "-c", "command -v bash")).inputStream.bufferedReader().readLine()?.isNotBlank() == true) "bash" else "/system/bin/sh"

                        val session = TerminalSession(
                            shell, ctx.filesDir.absolutePath, arrayOf(),
                            env.toTypedArray(), 5000, client
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

        // 顶部可折叠工具条（默认收起，仅占一条小把手，点击展开）
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(CColor(0xCC000000))
        ) {
            // 把手
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clickable { toolbarExpanded = !toolbarExpanded },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (toolbarExpanded) "▼ 收起" else "▲ Zorv 终端",
                    color = CColor(0xFFAAAAAA),
                    fontSize = 11.sp
                )
            }
            AnimatedVisibility(visible = toolbarExpanded) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "真 PTY · bash/sh · ANSI 颜色",
                        fontSize = 12.sp,
                        color = CColor(0xFFCCCCCC),
                        modifier = Modifier.weight(1f)
                    )
                    if (termError.value != null) {
                        TextButton(onClick = { termError.value = null }) { Text("重试", color = CColor(0xFFEF5350)) }
                    }
                    IconButton(
                        onClick = { showHelp = true },
                        modifier = Modifier.size(32.dp)
                    ) { Icon(Icons.Filled.Help, contentDescription = "帮助", tint = CColor.White) }
                    IconButton(
                        onClick = {
                            val tv = terminalViewState.value ?: return@IconButton
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            if (showIme.value) {
                                tv.requestFocus()
                                imm.showSoftInput(tv, InputMethodManager.SHOW_IMPLICIT)
                            } else {
                                imm.hideSoftInputFromWindow(tv.windowToken, 0)
                            }
                            showIme.value = !showIme.value
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (showIme.value) Icons.Default.KeyboardHide else Icons.Default.Keyboard,
                            contentDescription = "切换键盘",
                            tint = CColor.White
                        )
                    }
                }
            }
        }
    }

    if (showHelp) {
        HelpDialog(onDismiss = { showHelp = false })
    }
}

