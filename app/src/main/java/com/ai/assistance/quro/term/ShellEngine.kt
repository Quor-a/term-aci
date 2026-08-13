package com.ai.assistance.quro.term

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地 Shell 执行引擎（受控终端核心）。
 *
 * 通过 ProcessBuilder 调系统 `sh -c`，支持：
 *  - 当前工作目录（cwd）持久化与切换；
 *  - 持久环境变量；
 *  - 前台执行带超时（超时强制 destroy，默认 14s 适配控制端 15s 预算）；
 *  - 后台执行（jobId 追踪、输出累积、可 kill）。
 *
 * 无网络、无 GMS 依赖。命令在 App 进程上下文执行（非 root），
 * 仅可访问 App 私有区与系统已授权存储（受 Android 沙箱限制）。
 */
object ShellEngine {

    private const val DEFAULT_TIMEOUT_MS = 14_000L

    private val lock = Any()

    @Volatile
    private var cwd: String = ""

    private val env = mutableMapOf<String, String>()

    private val jobs = ConcurrentHashMap<Long, Job>()
    private val jobSeq = AtomicLong(1)

    data class ExecResult(
        val stdout: String,
        val exitCode: Int,
        val timedOut: Boolean
    )

    data class Job(
        val id: Long,
        val cmd: String,
        val cwd: String,
        val startedAt: Long,
        @Volatile var process: Process? = null,
        @Volatile var finished: Boolean = false,
        @Volatile var exitCode: Int = -1,
        val output: StringBuilder = StringBuilder()
    ) {
        fun snapshot(tail: Int): JobSnapshot =
            JobSnapshot(id, cmd, cwd, finished, exitCode, startedAt, output.length,
                if (output.length <= tail) output.toString() else output.substring(output.length - tail))
    }

    data class JobSnapshot(
        val id: Long, val cmd: String, val cwd: String,
        val finished: Boolean, val exitCode: Int, val startedAt: Long,
        val bytes: Int, val tail: String
    )

    // ── 初始化与状态 ────────────────────────────────────────

    fun init(context: Context) {
        synchronized(lock) {
            if (cwd.isBlank()) cwd = context.filesDir.absolutePath
        }
    }

    fun getCwd(): String = synchronized(lock) { cwd }
    fun setCwd(path: String): Boolean = synchronized(lock) {
        val f = File(path)
        return if (f.isDirectory) { cwd = f.absolutePath; true } else false
    }

    fun getEnv(): Map<String, String> = synchronized(lock) { env.toMap() }
    fun setEnv(k: String, v: String) = synchronized(lock) { env[k] = v }
    fun shellName(): String = "sh (" + (System.getProperty("os.name") ?: "Linux") + ")"

    // ── 前台执行（带超时，合并 stdout/stderr） ─────────────────

    fun exec(cmd: String, cwdOverride: String? = null, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ExecResult {
        val workDir = (cwdOverride?.takeIf { it.isNotBlank() } ?: getCwd())
        val pb = ProcessBuilder("sh", "-c", cmd).apply {
            directory(File(workDir))
            redirectErrorStream(true)
            synchronized(lock) { environment().putAll(env) }
        }
        return try {
            val p = pb.start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) {
                p.destroyForcibly()
                ExecResult(out + "\n[TermAci] 命令超时(${timeoutMs}ms)已强制终止", -1, true)
            } else {
                ExecResult(out, p.exitValue(), false)
            }
        } catch (e: Throwable) {
            ExecResult("", -1, false).let {
                ExecResult("[TermAci] 执行失败: ${e.javaClass.simpleName}: ${e.message}", -1, false)
            }
        }
    }

    // ── 后台执行（jobId 追踪） ───────────────────────────────

    fun execBg(cmd: String, cwdOverride: String? = null): Long {
        val id = jobSeq.getAndIncrement()
        val workDir = (cwdOverride?.takeIf { it.isNotBlank() } ?: getCwd())
        val job = Job(id, cmd, workDir, System.currentTimeMillis())
        jobs[id] = job
        Thread {
            try {
                val pb = ProcessBuilder("sh", "-c", cmd).apply {
                    directory(File(workDir))
                    redirectErrorStream(true)
                    synchronized(lock) { environment().putAll(env) }
                }
                val p = pb.start()
                job.process = p
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(1024)
                    var n: Int
                    while (r.read(buf).also { n = it } != -1) {
                        job.output.append(String(buf, 0, n))
                    }
                }
                job.exitCode = p.waitFor()
                job.finished = true
            } catch (e: Throwable) {
                job.output.append("\n[TermAci] 后台执行异常: ${e.message}")
                job.finished = true
                job.exitCode = -1
            }
        }.start()
        return id
    }

    fun listJobs(): List<JobSnapshot> = jobs.values.map { it.snapshot(0) }
    fun getJob(id: Long, tail: Int = 4000): JobSnapshot? = jobs[id]?.snapshot(tail)
    fun killJob(id: Long): Boolean {
        val j = jobs[id] ?: return false
        j.process?.destroyForcibly()
        j.finished = true
        return true
    }

    // ── 文件操作（受沙箱限制，仅可访问已授权路径） ─────────────

    fun readFile(path: String, maxBytes: Int = 512 * 1024): String {
        val f = File(path)
        if (!f.isFile) return "[TermAci] 文件不存在或不是普通文件: $path"
        return try {
            val bytes = f.readBytes().take(maxBytes).toByteArray()
            String(bytes, Charsets.UTF_8)
        } catch (e: Throwable) {
            "[TermAci] 读取失败: ${e.message}"
        }
    }

    fun writeFile(path: String, content: String, append: Boolean): Long {
        return try {
            val f = File(path)
            f.parentFile?.mkdirs()
            if (append) f.appendText(content, Charsets.UTF_8) else f.writeText(content, Charsets.UTF_8)
            f.length()
        } catch (e: Throwable) {
            -1L
        }
    }

    data class DirEntry(val name: String, val isDir: Boolean, val size: Long)

    fun listDir(path: String?): List<DirEntry> {
        val dir = File(path?.takeIf { it.isNotBlank() } ?: getCwd())
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))?.map {
            DirEntry(it.name, it.isDirectory, if (it.isDirectory) 0L else it.length())
        } ?: emptyList()
    }
}
