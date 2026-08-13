package com.ai.assistance.quro.term

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * TermAci「控制台」后端（SDUI 范式）。
 *
 * 控制台是**控制端**功能；受控端只暴露 console_ui / console_action 两个能力，由控制端
 * AciConsoleScreen 纯本地渲染。本后端持有业务状态（最近命令输出 / 上次操作 / cwd），
 * buildUiSnapshot() 只读状态成图（不触盘），applyAction() 才真正调 ShellEngine，
 * 由 Service 在后台线程调用（不阻塞 Binder/LocalSocket 线程）。
 *
 * input 提交兼容铁律（§14.3）：控制端回传 {value, key}，applyAction 必须**按 key 读参**。
 */
object TermAciConsoleBackend {

    @Volatile private var appCtx: Context? = null
    @Volatile private var lastOutput: String = ""
    @Volatile private var lastMsg: String = ""

    fun attachContext(ctx: Context) { appCtx = ctx.applicationContext }

    /** 生成当前 UI 快照（只读状态，非阻塞）。 */
    fun buildUiSnapshot(): JSONObject {
        val cwd = ShellEngine.getCwd()
        val shell = ShellEngine.shellName()
        val jobs = ShellEngine.listJobs().size
        val components = JSONArray()
        components.put(JSONObject().put("type", "heading").put("text", "TermAci 终端控制台"))
        components.put(
            JSONObject().put("type", "text")
                .put("text", "经 ACI console_ui / console_action 由控制端渲染（后端驱动，前端免发版）")
        )
        components.put(JSONObject().put("type", "text").put("text", "cwd=$cwd · $shell · 后台 $jobs 任务"))
        components.put(
            JSONObject().put("type", "card")
                .put("title", "最近命令输出")
                .put("body", if (lastOutput.isNotBlank()) lastOutput.take(800) else "（暂无）")
        )
        components.put(
            JSONObject().put("type", "input")
                .put("key", "cmd").put("label", "执行命令").put("placeholder", "ls -la /sdcard")
                .put("value", "").put("action", "exec")
        )
        components.put(JSONObject().put("type", "button").put("action", "status").put("label", "终端状态"))
        components.put(JSONObject().put("type", "button").put("action", "jobs").put("label", "后台任务"))
        components.put(
            JSONObject().put("type", "input")
                .put("key", "path").put("label", "列出目录").put("placeholder", "可空=当前 cwd")
                .put("value", "").put("action", "list_dir")
        )
        components.put(JSONObject().put("type", "divider"))
        components.put(
            JSONObject().put("type", "text")
                .put("text", if (lastMsg.isNotBlank()) "上次操作：$lastMsg" else "（暂无操作）")
        )
        components.put(JSONObject().put("type", "listitem").put("text", "受控端包名: com.ai.assistance.quro.term"))
        components.put(JSONObject().put("type", "listitem").put("text", "引擎: ShellEngine（本地 Shell，无网络）"))

        return JSONObject()
            .put("title", "TermAci 终端控制台")
            .put("subtitle", "后端驱动 · 控制端渲染（ACI）")
            .put("updatedAt", System.currentTimeMillis())
            .put("components", components)
    }

    /** 处理前端回传的 action，真正驱动终端。后台线程调用。 */
    fun applyAction(action: String, payload: JSONObject?): JSONObject {
        val p = payload ?: JSONObject()
        val msg = when (action) {
            "exec" -> {
                val key = p.optString("key", "cmd")
                val cmd = p.optString(key, p.optString("value", "")).trim()
                if (cmd.isEmpty()) "请输入命令" else {
                    val r = ShellEngine.exec(cmd, null, 14_000L)
                    val head = "${if (r.timedOut) "TIMEOUT" else "exit=${r.exitCode}"} · ${r.stdout.lineSequence().count()} 行\n"
                    lastOutput = head + r.stdout.take(2000)
                    "${if (r.timedOut) "超时" else "退出 ${r.exitCode}"} · ${r.stdout.lineSequence().count()} 行"
                }
            }
            "status" -> {
                val envCount = ShellEngine.getEnv().size
                val s = "cwd=${ShellEngine.getCwd()} · ${ShellEngine.shellName()} · 后台 ${ShellEngine.listJobs().size} 任务 · $envCount 环境变量"
                lastOutput = s
                s
            }
            "jobs" -> {
                val arr = ShellEngine.listJobs()
                val sb = StringBuilder()
                arr.forEachIndexed { i, j ->
                    if (i > 0) sb.append("\n")
                    sb.append("#${j.id} ${if (j.finished) "[完成 ${j.exitCode}]" else "[运行中]"} ${j.cmd}")
                }
                lastOutput = if (sb.isEmpty()) "无后台任务" else "后台任务 ${arr.size} 个：\n$sb"
                "列出 ${arr.size} 个后台任务"
            }
            "list_dir" -> {
                val key = p.optString("key", "path")
                val path = p.optString(key, p.optString("value", "")).trim()
                val entries = ShellEngine.listDir(if (path.isEmpty()) null else path)
                val sb = StringBuilder()
                entries.forEachIndexed { i, e ->
                    if (i > 0) sb.append("\n")
                    sb.append(if (e.isDir) "📁 ${e.name}/" else "📄 ${e.name} (${e.size})")
                }
                lastOutput = if (sb.isEmpty()) "目录为空或不存在" else "${entries.size} 个条目：\n$sb"
                "列出 ${entries.size} 个条目"
            }
            else -> "未知 action: $action"
        }
        lastMsg = msg
        return JSONObject().put("ok", true).put("action", action).put("message", msg)
    }
}
