# TermAci — Zorv AI 本地终端受控端

![Platform](https://img.shields.io/badge/platform-Android-3DDC84)
![Version](https://img.shields.io/badge/version-1.4.0-blue)
![License](https://img.shields.io/badge/license-OpenSource-green)

TermAci 是一个 Android 本地终端应用，同时作为 **Zorv AI** 的 ACI 受控端（Agent-Controlled Interface），可被 Zorv AI 主程序在端侧自动发现并执行 shell 命令、读写文件。它内置交互式终端与 Shell 执行引擎，既能被 AI 后台静默操控，也能独立交互使用。

## ✨ 特性

- **交互式本地终端**（终端 Tab）：
  - 命令输入 / 输出日志（等宽字体，区分输入 / 输出 / 系统提示）。
  - 内置命令：`cd`（切换工作目录并持久化）、`pwd`、`clear`。
  - 命令历史（↑/↓ 翻历史），快捷按钮 `pwd` / `ls -la` / `clear`，实时显示当前 cwd。
- **Shell 执行引擎**（`ShellEngine`，纯 `ProcessBuilder` + `sh`）：
  - 支持 cwd、持久环境变量。
  - 前台执行带超时（默认 14s，适配控制端 15s 预算；超时强制 `destroyForcibly`）。
  - 后台执行（jobId 追踪 + 输出累积 + 可 `kill`）。
  - stdout/stderr 合并捕获。
- **ACI 受控端（9 项能力）**：Zorv AI 可在后台静默执行命令、管理文件、查询状态。

## 🎨 图标（ZorvAI 风格自适应启动图标）

- **底色**：深空线性渐变 `#0B2A3A` → `#06121F`，叠加青色对角点缀 `#16C9C9`（ZorvAI 品牌视觉）。
- **主体**：白色终端窗口 + 翠绿 `>_` 提示符与交通灯节点（`#34D399`），呼应「终端」语义。
- **自适应图标（Adaptive Icon）**：Android 8.0+ 自动适配设备形状与主题。

## 🔐 权限

| 权限 | 用途 | 类型 |
| --- | --- | --- |
| `ai.aci.permission.CALL` | ACI 调用（受控端 ↔ 控制端） | ACI 自定义 |
| `ai.aci.permission.DISCOVER` | ACI 发现 | ACI 自定义 |
| `ai.aci.permission.CALL_DANGEROUS` | ACI 危险能力调用 | ACI 自定义 |

> TermAci 不申请任何 Android 危险权限；命令在 App 沙箱上下文执行，默认工作于 App `files/` 下，访问 Download/Documents 等需对应授权。

## 🧩 ACI 能力清单

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

## 🖥️ 操控台（Console）

本 App 作为受控端，向控制端暴露 `console_ui` / `console_action` 双通道（遵循《ACI 开发者手册》§14）：

- **`console_ui`**：返回终端面板的 SDUI 快照（`snapshot` / `title`），标记 `FLAG_BACKGROUND | FLAG_NO_UI`。
- **`console_action`**：入参 `action` / `payload`，在后台线程驱动 Shell 引擎（执行/后台/取输出/终止）。

SDUI 词汇：`heading` / `text` / `card` / `button` / `divider` / `spacer` / `listitem` / `input`。**受控端不内置自测调试台**，交互与可视化由控制端统一渲染。

## ⚠️ 已知限制

- **非 root**：命令在 App 进程上下文执行，仅可访问 App 私有区与 Android 沙箱已授权路径；无法访问系统全局文件。
- **无完整 Linux 环境**：不内置 busybox/bootstrap，依赖系统自带 `toybox`/`sh` 能力；复杂命令（包管理、编译链）不可用。
- **长任务用后台**：前台命令受 14s 硬上限约束，耗时任务请用 `term_exec_bg` 并以 `term_job_output` 取结果。

## 🧱 技术栈

- Kotlin + Jetpack Compose（Material 3）
- ACI 框架：`aidl-aci-core`（AIDL + LocalSocket 抽象命名空间双通道）
- 执行：纯 `java.lang.ProcessBuilder`（无第三方库、无 GMS 依赖）
- 原生终端：集成 `terminal-emulator`（NDK 构建，含 arm64-v8a / x86_64 / x86 / armeabi-v7a）

## 📦 安装

- 从 [GitHub Releases](https://github.com/Quor-a/term-aci/releases) 下载最新 APK，允许「未知来源」后安装。
- 或开发机自行构建（见下）。

## 🛠️ 构建（开发机）

> 工程路径**不要含空格**（如 `Calw OS-project`），否则 NDK 构建会因 `APP_BUILD_SCRIPT` 参数被拆断而失败；可用无空格 junction（如 `D:\TermAci`）指向真实工程再构建。

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 🔌 接入 Zorv AI

作为第三方受控端，遵循《ACI 开发者手册》§16：剥离 AAR 内 `ai.aci.permission.*` 定义节点（`tools:node="remove"`），仅引用 Zorv AI 主程序已定义的权限，避免异签名安装冲突。

## 📄 许可

开源许可（见仓库 LICENSE）。
