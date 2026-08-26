# Zorv 终端（Zorv Terminal）

ZorvAI（QuroAI）内置的**应用内 Linux 终端 / 沙箱**能力。在 Android 上无需 root，即可获得一个接近完整的 Linux 用户态环境，直接在对话框里跑 Shell、Python 与编译工具链，供 AI 与用户操作手机。

> **状态说明**：终端能力当前已整合进 ZorvAI 主仓库（`QuroAI`）的 `app/src/main/java/com/ai/assistance/quro/core/terminal` 与 `core/linux` 以及 CMS 引擎。本仓库保留终端相关的设计文档与独立终端 App 的归档说明。

## 架构

- **proot 用户态沙箱**：由 `proot` 二进制（以 `libproot.so` 形式内置，`nativeLibraryDir` + `assets/linux_env` 兜底）在应用私有目录内模拟 Linux 文件系统根，无需 root 即可获得隔离的文件系统视图。
- **Ubuntu 24.04 ARM64 rootfs**：首次进入终端时自动从 Ubuntu 官方镜像（aliyun / tuna / cdimage.ubuntu.com）下载 `ubuntu-base-24.04` base rootfs 并解压到应用私有目录；arm64 架构自动切换 `ubuntu-ports` 源。rootfs **不随包内置**，显著减小 APK 体积。
- **内置基础命令**：`libbash.so` / `libbusybox.so` 提供 bash 与 busybox（awk / grep / sed / tar / wget 等 30+ 命令），并启用 `link2symlink` 支持。
- **交互终端**：`QuroShellSession`（PTY）常驻 `/bin/sh`，获得完整写能力与 `python3`；`QuroTerminalController` 为会话控制器（proot 优先，否则回退设备 `sh`）。
- **CMS 引擎**：`bootstrap.sh` 在沙箱内一键拉起 NODE / PYTHON / RUST / GO / JAVA 共享运行时，供 `quro.code`、`quro.terminal` 等模块使用。

## ACI 受控终端（设计归档）

独立终端 App（`com.ai.assistance.quro.term`）曾作为 **ACI 受控端**实现：通过 AIDL + LocalSocket 暴露
`term_exec` / `term_exec_bg` / `term_jobs` / `term_job_output` / `term_kill` /
`term_read_file` / `term_write_file` / `term_list_dir` / `term_status` /
`console_ui` / `console_action` 等能力，由控制端 ZorvAI 经 `ai.aci.permission.CALL` 绑定调用。
该独立 App 已归档，能力并入主程序。

## 使用入口

- 对话框输入「+」→ 终端，或 AI 调用 `ui_open_terminal`
- 首次进入提示「安装 Linux 环境」，自动下载并解压 Ubuntu 24.04 rootfs
- 终端内可直接执行 `ls /`、`python3`、`apt-get`（联网）等

## 诊断

部署 / 运行日志写到手机公共目录 `Download/QuroAI_logs/`，无需 adb 即可取出。
