package com.ai.assistance.quro.term

import ai.aidl.aci.core.AidlAciError
import ai.aidl.aci.core.AidlAciRequest
import ai.aidl.aci.core.AidlAciResponse
import ai.aidl.aci.core.BaseAidlAciService
import ai.aidl.aci.core.Capability
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ACI 受控端 Service：向 Zorv AI（控制端）暴露本地终端能力（Termux 风格的受控终端）。
 *
 * 接入方式遵循《ACI 开发者手册》§4 + §16/§20：
 *  - 继承 BaseAidlAciService（新契约 ai.aidl.aci.core.*）
 *  - 基类 onCreate() 自动启动 LocalSocket 高速通道 Server（端点=本包名），与 AIDL 并存
 *  - onCreate() 用 try-catch 包裹 super.onCreate()
 *  - onCreateCapabilities 注册 9 项终端能力；onCall 处理调用；onCheckPermission 做调用方白名单
 *  - 命令执行在后台线程，用 CountDownLatch 限时（前景命令 ≤14s，适配控制端 15s 预算）
 *
 * 本地 Shell 执行，无网络，故 Manifest 不含 INTERNET/定位权限。
 */
class TermAciService : BaseAidlAciService() {

    companion object {
        private const val TAG = "TermACI"
        private const val ZORV_PKG = "com.ai.assistance.quro"
        /** 控制端 QuroAidlAciManager.callTimeoutMs = 15_000L；handler 硬上限留 1s 余量 */
        private const val HARD_TIMEOUT_S = 14L
        private val executor = Executors.newCachedThreadPool()
    }

    override fun onCreate() {
        try {
            super.onCreate()
            ShellEngine.init(applicationContext)
            Log.i(TAG, "onCreate 完成（AIDL + LocalSocket 双通道已就绪）")
        } catch (e: Throwable) {
            Log.e(TAG, "super.onCreate() 失败: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    override fun onCreateCapabilities(caps: MutableList<Capability>) {
        // 1. 前台执行命令
        caps.add(
            Capability.create(
                "term_exec",
                "在前台执行一条 shell 命令（默认 sh -c），返回合并后的输出与退出码。超时（默认 14s）会强制终止。长任务请用 term_exec_bg。"
            )
                .addParam("cmd", "string", true, "要执行的 shell 命令")
                .addParam("cwd", "string", false, "工作目录（可空则用当前 cwd）")
                .addParam("timeout", "string", false, "超时毫秒（可空则用默认 14000）")
                .addResult("stdout", "string", "命令输出（stdout 与 stderr 已合并）")
                .addResult("exitCode", "string", "退出码（超时/异常为 -1）")
                .addResult("timedOut", "string", "是否超时：true/false")
                .addResult("summary", "string", "可读摘要")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 2. 后台执行
        caps.add(
            Capability.create(
                "term_exec_bg",
                "在后台执行一条 shell 命令，立即返回 jobId；可用 term_job_output / term_kill 追踪与终止。"
            )
                .addParam("cmd", "string", true, "要执行的 shell 命令")
                .addParam("cwd", "string", false, "工作目录（可空则用当前 cwd）")
                .addResult("jobId", "string", "后台任务 id")
                .addResult("summary", "string", "可读摘要")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 3. 列出后台任务
        caps.add(
            Capability.create(
                "term_jobs",
                "列出当前所有后台 shell 任务（id / 命令 / 状态 / 退出码）。"
            )
                .addResult("jobs", "string", "任务 JSON 数组")
                .addResult("count", "string", "任务数量")
                .addResult("summary", "string", "可读摘要")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 4. 获取后台任务输出
        caps.add(
            Capability.create(
                "term_job_output",
                "获取某后台任务的累计输出（尾部截断）。"
            )
                .addParam("jobId", "string", true, "后台任务 id")
                .addParam("tail", "string", false, "尾部保留字符数（可空默认 4000）")
                .addResult("output", "string", "任务输出")
                .addResult("finished", "string", "是否结束：true/false")
                .addResult("exitCode", "string", "退出码（-1=运行中/异常）")
                .addResult("summary", "string", "可读摘要")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 5. 终止后台任务
        caps.add(
            Capability.create(
                "term_kill",
                "强制终止一个后台 shell 任务。"
            )
                .addParam("jobId", "string", true, "后台任务 id")
                .addResult("killed", "string", "是否成功终止：true/false")
                .addResult("summary", "string", "可读摘要")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 6. 读取文件
        caps.add(
            Capability.create(
                "term_read_file",
                "读取文本文件内容（受 Android 沙箱限制，仅可访问已授权路径；超大文件截断到 512KB）。"
            )
                .addParam("path", "string", true, "文件绝对路径")
                .addResult("content", "string", "文件内容")
                .addResult("bytes", "string", "字节数")
                .addResult("summary", "string", "可读摘要")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 7. 写入文件
        caps.add(
            Capability.create(
                "term_write_file",
                "写入文本到文件（自动创建父目录；append=true 追加，否则覆盖）。"
            )
                .addParam("path", "string", true, "文件绝对路径")
                .addParam("content", "string", true, "写入内容")
                .addParam("append", "string", false, "是否追加（可空默认 false）")
                .addResult("bytes", "string", "写入后文件字节数（-1=失败）")
                .addResult("summary", "string", "可读摘要")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 8. 列出目录
        caps.add(
            Capability.create(
                "term_list_dir",
                "列出目录内容（目录优先、再按名称排序），返回条目名/是否目录/大小。"
            )
                .addParam("path", "string", false, "目录路径（可空则用当前 cwd）")
                .addResult("entries", "string", "条目 JSON 数组")
                .addResult("count", "string", "条目数量")
                .addResult("summary", "string", "可读摘要")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 9. 终端状态
        caps.add(
            Capability.create(
                "term_status",
                "返回终端状态：当前 cwd、shell 名、环境变量、后台任务数。"
            )
                .addResult("cwd", "string", "当前工作目录")
                .addResult("shell", "string", "shell 名称")
                .addResult("env", "string", "环境变量 JSON")
                .addResult("jobCount", "string", "后台任务数")
                .addResult("summary", "string", "可读摘要")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )
    }

    override fun onCheckPermission(request: AidlAciRequest?, callerPkg: String?): Boolean {
        return callerPkg == ZORV_PKG || callerPkg == packageName
    }

    override fun onCall(request: AidlAciRequest?): AidlAciResponse {
        if (request == null) return AidlAciResponse.error(AidlAciError.REQUEST_NULL, "null")
        return when (request.capability) {
            "term_exec" -> handleExec(request)
            "term_exec_bg" -> handleExecBg(request)
            "term_jobs" -> handleJobs(request)
            "term_job_output" -> handleJobOutput(request)
            "term_kill" -> handleKill(request)
            "term_read_file" -> handleReadFile(request)
            "term_write_file" -> handleWriteFile(request)
            "term_list_dir" -> handleListDir(request)
            "term_status" -> handleStatus(request)
            else -> AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND, "unknown: ${request.capability}")
        }
    }

    // ── 能力处理 ──────────────────────────────────────────────

    private fun handleExec(req: AidlAciRequest): AidlAciResponse = runNet {
        val cmd = req.params?.getString("cmd")
        if (cmd.isNullOrBlank()) {
            return@runNet AidlAciResponse.error(AidlAciError.BAD_REQUEST, "missing param: cmd")
        }
        val cwd = req.params?.getString("cwd")
        val timeout = req.params?.getString("timeout")?.toLongOrNull() ?: 14_000L
        val r = ShellEngine.exec(cmd, cwd, timeout)
        AidlAciResponse.success()
            .putResult("stdout", r.stdout)
            .putResult("exitCode", r.exitCode.toString())
            .putResult("timedOut", r.timedOut.toString())
            .putResult(
                "summary",
                "exit=${(if (r.timedOut) "TIMEOUT" else r.exitCode)} · ${r.stdout.lineSequence().count()} 行输出"
            )
    }

    private fun handleExecBg(req: AidlAciRequest): AidlAciResponse = runNet {
        val cmd = req.params?.getString("cmd")
        if (cmd.isNullOrBlank()) {
            return@runNet AidlAciResponse.error(AidlAciError.BAD_REQUEST, "missing param: cmd")
        }
        val cwd = req.params?.getString("cwd")
        val id = ShellEngine.execBg(cmd, cwd)
        AidlAciResponse.success()
            .putResult("jobId", id.toString())
            .putResult("summary", "已后台启动任务 #$id：$cmd")
    }

    private fun handleJobs(@Suppress("UNUSED_PARAMETER") req: AidlAciRequest): AidlAciResponse = runNet {
        val arr = JSONArray()
        val sb = StringBuilder()
        ShellEngine.listJobs().forEachIndexed { i, j ->
            arr.put(JSONObject().apply {
                put("id", j.id)
                put("cmd", j.cmd)
                put("cwd", j.cwd)
                put("finished", j.finished)
                put("exitCode", j.exitCode)
            })
            if (i > 0) sb.append("\n")
            sb.append("#${j.id} ${if (j.finished) "[完成 ${j.exitCode}]" else "[运行中]"} ${j.cmd}")
        }
        AidlAciResponse.success()
            .putResult("jobs", arr.toString())
            .putResult("count", ShellEngine.listJobs().size.toString())
            .putResult("summary", if (sb.isEmpty()) "无后台任务" else "后台任务 ${ShellEngine.listJobs().size} 个：\n$sb")
    }

    private fun handleJobOutput(req: AidlAciRequest): AidlAciResponse = runNet {
        val jobId = req.params?.getString("jobId")?.toLongOrNull()
        if (jobId == null) {
            return@runNet AidlAciResponse.error(AidlAciError.BAD_REQUEST, "missing/invalid param: jobId")
        }
        val tail = req.params?.getString("tail")?.toIntOrNull() ?: 4000
        val snap = ShellEngine.getJob(jobId, tail)
        if (snap == null) {
            return@runNet AidlAciResponse.error(AidlAciError.BAD_REQUEST, "任务不存在: $jobId")
        }
        AidlAciResponse.success()
            .putResult("output", snap.tail)
            .putResult("finished", snap.finished.toString())
            .putResult("exitCode", snap.exitCode.toString())
            .putResult("summary", "#$jobId ${if (snap.finished) "完成(${snap.exitCode})" else "运行中"} · ${snap.bytes} 字节")
    }

    private fun handleKill(req: AidlAciRequest): AidlAciResponse = runNet {
        val jobId = req.params?.getString("jobId")?.toLongOrNull()
        if (jobId == null) {
            return@runNet AidlAciResponse.error(AidlAciError.BAD_REQUEST, "missing/invalid param: jobId")
        }
        val ok = ShellEngine.killJob(jobId)
        AidlAciResponse.success()
            .putResult("killed", ok.toString())
            .putResult("summary", if (ok) "已终止任务 #$jobId" else "任务不存在: $jobId")
    }

    private fun handleReadFile(req: AidlAciRequest): AidlAciResponse = runNet {
        val path = req.params?.getString("path")
        if (path.isNullOrBlank()) {
            return@runNet AidlAciResponse.error(AidlAciError.BAD_REQUEST, "missing param: path")
        }
        val content = ShellEngine.readFile(path)
        AidlAciResponse.success()
            .putResult("content", content)
            .putResult("bytes", content.toByteArray(Charsets.UTF_8).size.toString())
            .putResult("summary", "读取 $path（${content.toByteArray(Charsets.UTF_8).size} 字节）")
    }

    private fun handleWriteFile(req: AidlAciRequest): AidlAciResponse = runNet {
        val path = req.params?.getString("path")
        val content = req.params?.getString("content")
        if (path.isNullOrBlank() || content == null) {
            return@runNet AidlAciResponse.error(AidlAciError.BAD_REQUEST, "missing param: path / content")
        }
        val append = req.params?.getString("append")?.toBooleanStrictOrNull() ?: false
        val bytes = ShellEngine.writeFile(path, content, append)
        AidlAciResponse.success()
            .putResult("bytes", bytes.toString())
            .putResult("summary", if (bytes >= 0) "已写入 $path（$bytes 字节）" else "写入失败: $path")
    }

    private fun handleListDir(req: AidlAciRequest): AidlAciResponse = runNet {
        val path = req.params?.getString("path")
        val entries = ShellEngine.listDir(path)
        val arr = JSONArray()
        val sb = StringBuilder()
        entries.forEachIndexed { i, e ->
            arr.put(JSONObject().apply {
                put("name", e.name)
                put("isDir", e.isDir)
                put("size", e.size)
            })
            if (i > 0) sb.append("\n")
            sb.append(if (e.isDir) "📁 ${e.name}/" else "📄 ${e.name} (${e.size})")
        }
        AidlAciResponse.success()
            .putResult("entries", arr.toString())
            .putResult("count", entries.size.toString())
            .putResult("summary", if (sb.isEmpty()) "目录为空或不存在" else "${entries.size} 个条目：\n$sb")
    }

    private fun handleStatus(@Suppress("UNUSED_PARAMETER") req: AidlAciRequest): AidlAciResponse = runNet {
        val env = JSONObject()
        ShellEngine.getEnv().forEach { (k, v) -> env.put(k, v) }
        AidlAciResponse.success()
            .putResult("cwd", ShellEngine.getCwd())
            .putResult("shell", ShellEngine.shellName())
            .putResult("env", env.toString())
            .putResult("jobCount", ShellEngine.listJobs().size.toString())
            .putResult(
                "summary",
                "cwd=${ShellEngine.getCwd()} · ${ShellEngine.shellName()} · 后台 ${ShellEngine.listJobs().size} 任务"
            )
    }

    // ── 后台执行 + 限时 ────────────────────────────────────────

    /**
     * 在后台线程执行阻塞 IO，并以 HARD_TIMEOUT_S 为上限等待结果。
     * 超时返回 TIMEOUT，异常返回 INTERNAL_ERROR —— 绝不抛到 Binder 线程。
     */
    private inline fun runNet(crossinline block: () -> AidlAciResponse): AidlAciResponse {
        val latch = CountDownLatch(1)
        var result: AidlAciResponse? = null
        executor.submit {
            try {
                result = block()
            } catch (e: Throwable) {
                result = AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "term error: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        val ok = latch.await(HARD_TIMEOUT_S, TimeUnit.SECONDS)
        if (!ok) return AidlAciResponse.error(AidlAciError.TIMEOUT, "term request timed out")
        return result ?: AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "no result")
    }
}
