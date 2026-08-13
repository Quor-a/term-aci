# TermAci — 基于 ACI 框架的本地终端受控端（对标 Termux 本地 shell）

TermAci 是一个 Android 本地终端应用，同时作为 **Zorv AI** 的 ACI 受控端（Agent-Controlled Interface），可被 Zorv AI 主程序在端侧自动发现并执行 shell 命令、读写文件。对标 Termux 的「本地终端 / 命令执行」核心能力，但以受控端形式存在——既能被 AI 后台静默操控，也能独立交互使用。

## 功能（v1.0.0）

- **交互式本地终端**（终端 Tab）：
  - 命令输入 / 输出日志（等宽字体，区分输入 / 输出 / 系统提示）。
  - 内置命令：`cd`（切换工作目录并持久化）、`pwd`、`clear`。
  - 命令历史（↑/↓ 翻历史）。
  - 快捷按钮：`pwd` / `ls -la` / `clear`，实时显示当前 cwd。
- **Shell 执行引擎**（`ShellEngine`，纯 `ProcessBuilder` + `sh`）：
  - 支持 cwd、持久环境变量。
  - 前台执行带超时（默认 14s，适配控制端 15s 预算；超时强制 `destroyForcibly`）。
  - 后台执行（jobId 追踪 + 输出累积 + 可 `kill`）。
  - stdout/stderr 合并捕获。
- **ACI 受控端（9 项能力）**：Zorv AI 可在后台静默执行命令、管理文件、查询状态。
- **调试操控台**（操控台 Tab）：内置面板自绑定 ACI Service，可视化双通道状态与能力列表，手动填参调 `call()`。

## ACI 能力清单

| 能力 | 说明 |
| --- | --- |
| `term_exec` | 前台执行命令（cmd/cwd/timeout）→ stdout(合并)/exitCode/timedOut |
| `term_exec_bg` | 后台执行命令 → jobId |
| `term_jobs` | 列出后台任务（id/cmd/状态/exitCode） |
| `term_job_output` | 获取后台任务累计输出（尾部截断） |
| `term_kill` | 强制终止后台任务 |
| `term_read_file` | 读取文本文件（受沙箱限制，超大截断 512KB） |
| `term_write_file` | 写入文本（自动建父目录，append/clear） |
| `term_list_dir` | 列出目录（目录优先排序，条目名/类型/大小） |
| `term_status` | 终端状态：cwd/shell/env/后台任务数 |

所有能力均为 `BACKGROUND + NO_UI`，适合由 AI 在后台静默调用。

## 已知限制

- **非 root**：命令在 App 进程上下文执行，仅可访问 App 私有区与 Android 沙箱已授权路径（Download/Documents 等需存储权限或 SAF，本 v1.0.0 未申请全量存储权限，默认在 App `files/` 下工作）。无法像 root 版 Termux 那样访问系统全局文件。
- **无完整 Linux 环境**：不内置 busybox/bootstrap，依赖系统自带 `toybox`/`sh` 能力；复杂命令（包管理、编译链）不可用。
- **长任务用后台**：前台命令受 14s 硬上限约束，耗时任务请用 `term_exec_bg` 并以 `term_job_output` 取结果。

## 技术栈

- Kotlin + Jetpack Compose（Material 3）
- ACI 框架：`aidl-aci-core`（AIDL + LocalSocket 抽象命名空间双通道）
- 执行：纯 `java.lang.ProcessBuilder`（无第三方库、无 GMS 依赖）

## 构建

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 接入 Zorv AI

作为第三方受控端，遵循《ACI 开发者手册》§16：剥离 AAR 内 `ai.aci.permission.*` 定义节点（`tools:node="remove"`），仅引用 Zorv AI 主程序已定义的权限，避免异签名安装冲突。

## 许可

开源许可（见仓库 LICENSE）。
