# Zorv 终端（Zorv Terminal）

ZorvAI（QuroAI）内置的**应用内 Linux 终端 / 沙箱**能力。在 Android 上无需 root，即可获得一个接近完整的 Linux 用户态环境，直接在对话框里跑 Shell、Python 与编译工具链，供 AI 与用户操作手机。

> **状态说明**：终端能力当前已整合进 ZorvAI 主仓库（`QuroAI`）的 `app/src/main/java/com/ai/assistance/quro/core/terminal` 与 `core/linux` 以及 CMS 引擎。本仓库保留终端相关的设计文档与独立终端 App 的归档说明。
>
> **对外集成能力（已支持）**：Zorv 终端除本地 Shell 沙箱外，还作为 ZorvAI 生态的**受控执行端**，向控制端开放 **ACI / ACIHTTP / 操控台 / 本地 API / MCP** 五类标准接口，详见下文「对外集成能力」。

## 架构

- **proot 用户态沙箱**：由 `proot` 二进制（以 `libproot.so` 形式内置，`nativeLibraryDir` + `assets/linux_env` 兜底）在应用私有目录内模拟 Linux 文件系统根，无需 root 即可获得隔离的文件系统视图。
- **Ubuntu 24.04 ARM64 rootfs**：首次进入终端时自动从 Ubuntu 官方镜像（aliyun / tuna / cdimage.ubuntu.com）下载 `ubuntu-base-24.04` base rootfs 并解压到应用私有目录；arm64 架构自动切换 `ubuntu-ports` 源。rootfs **不随包内置**，显著减小 APK 体积。
- **内置基础命令**：`libbash.so` / `libbusybox.so` 提供 bash 与 busybox（awk / grep / sed / tar / wget 等 30+ 命令），并启用 `link2symlink` 支持。
- **交互终端**：`QuroShellSession`（PTY）常驻 `/bin/sh`，获得完整写能力与 `python3`；`QuroTerminalController` 为会话控制器（proot 优先，否则回退设备 `sh`）。
- **CMS 引擎**：`bootstrap.sh` 在沙箱内一键拉起 NODE / PYTHON / RUST / GO / JAVA 共享运行时，供 `quro.code`、`quro.terminal` 等模块使用。

## 对外集成能力（已支持）

Zorv 终端不只是本地 Shell 沙箱，还作为 ZorvAI 生态的**受控执行端**，向控制端（PC / 网页操控台 / 第三方 Agent / 任意 MCP 客户端）开放五类标准接口：

### 1. ACI（AIDL 受控端）
通过 AIDL + LocalSocket 暴露 `term_exec` / `term_exec_bg` / `term_jobs` / `term_job_output` / `term_kill` /
`term_read_file` / `term_write_file` / `term_list_dir` / `term_status` /
`console_ui` / `console_action` 等能力，由控制端经 `ai.aci.permission.CALL` 绑定调用。

### 2. ACIHTTP（HTTP 传输层）
在 ACI 能力之上提供 **HTTP 长连接传输层**：控制端通过本地 HTTP 端点（localhost）下发 ACI 指令、终端回传结构化结果，无需 AIDL 绑定，天然跨进程 / 跨设备（配端口转发即可远程驱动）。适用于非 Android 控制端（桌面 Agent、脚本、CI）直接调用，是 ACI 的轻量跨平台替代通道。

### 3. 操控台（Console / 远程 UI）
提供**后端驱动 UI** 的操控台能力：终端内置 Console 后端（`ConsoleBackend`），把 TerminalSession / 文件浏览器 / 工具执行等状态以事件流形式推送给控制端，控制端渲染远端 UI 并向终端回传用户操作（`console_ui` / `console_action`）。即「控制端出界面、终端出执行」的范式，已适配到受控浏览器与远端控制台。

### 4. 本地 API
终端暴露一个**本地 HTTP API**（默认 localhost 端口），聚合终端 / 文件 / 作业 / 状态查询，供脚本与第三方工具以 REST 风格直接驱动，是 ACIHTTP 之上更友好的封装层。

### 5. MCP（Model Context Protocol）
终端可作为 **MCP Server** 运行，将 `term_exec` / `term_read_file` / `term_write_file` / `term_list_dir` 等能力暴露为标准 MCP 工具，供任意兼容 MCP 的客户端（Claude Desktop、JetBrains、自定义 Agent）以统一协议调用；同时支持经 `McpAciBridge` 把外部 MCP 工具反向桥接进 ACI 控制端，打通「MCP ↔ ACI」双向通道。

## 使用入口

- 对话框输入「+」→ 终端，或 AI 调用 `ui_open_terminal`
- 首次进入提示「安装 Linux 环境」，自动下载并解压 Ubuntu 24.04 rootfs
- 终端内可直接执行 `ls /`、`python3`、`apt-get`（联网）等

## 诊断

部署 / 运行日志写到手机公共目录 `Download/QuroAI_logs/`，无需 adb 即可取出。
