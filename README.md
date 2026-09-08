# Tapcreator

**AI 自主创作助手 | Autonomous AI Creation Assistant**

[中文](#中文) | [English](#english)

---

## 中文

### 概述

Tapcreator 是一款 Android 端 AI 自主创作应用。它内置一个自主 Agent（ReAct Loop），以文本模型为大脑，反复「思考→行动→观察」，直至完成用户诉求。Agent 可调用多种工具：文本/图片/视频/音频生成、联网搜索、素材库管理、卡片 CRUD、记忆系统、设计 Skill 系统、Alpine Linux 沙箱（shell 执行/Python 脚本/包安装），以及 MCP 插件扩展。

### 功能特性

- **自主 Agent**：ReAct Loop 架构，自动规划、执行、复盘、修正
- **多模态生成**：文本、图片、视频、音频一键生成
- **Alpine Linux 沙箱**：设备内 PRoot 沙箱，可执行 shell 命令、运行 Python 脚本、安装 apk 包
- **MCP 插件系统**：支持 stdio 子进程和 HTTP 远程两种 MCP 服务器
- **联网搜索**：内置 Bing 搜索 + Python 解析，支持配置上游搜索 API
- **记忆系统**：会话级持久化记忆，Agent 可自主 recall/memorize
- **技能系统**：内置预设 + 用户自定义设计 Skill，Agent 可自主安装/卸载
- **画布式工作空间**：可缩放平移的创作画布，卡片拖拽、参考连线、批量管理
- **关系图**：卡片引用关系可视化，支持链路追踪与复盘
- **RPM 限流器**：滑动窗口限流，避免上游 API 限频
- **成品卡预览**：图片放大、视频播放、下载到相册

### 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 架构 | MVVM + Hilt DI |
| 数据库 | Room (SQLite) |
| 网络 | OkHttp + Retrofit + kotlinx-serialization |
| 图片 | Coil |
| 视频 | ExoPlayer |
| 沙箱 | PRoot (Alpine Linux) |
| 构建 | Gradle KTS + AGP 8.5.2 |

### 版本

**v1.1.0** — 新增 Alpine 沙箱、MCP 插件系统、设计 Skill 系统、RPM 限流器、多项 bug 修复

### 构建

```bash
./gradlew assembleDebug
```

---

## English

### Overview

Tapcreator is an Android autonomous AI creation app. It features a built-in autonomous Agent (ReAct Loop) that uses a text model as its brain, repeatedly cycling through "Think → Act → Observe" until the user's request is fulfilled. The Agent can invoke various tools: text/image/video/audio generation, web search, asset library management, card CRUD, memory system, self-evolving skill system, Alpine Linux sandbox (shell execution/Python scripts/package installation), and MCP plugin extensions.

### Features

- **Autonomous Agent**: ReAct Loop architecture with auto-planning, execution, review, and correction
- **Multi-modal Generation**: One-click text, image, video, and audio generation
- **Alpine Linux Sandbox**: On-device PRoot sandbox for shell commands, Python scripts, and apk package installation
- **MCP Plugin System**: Supports stdio subprocess and HTTP remote MCP servers
- **Web Search**: Built-in Bing search + Python parsing, with optional upstream search API
- **Memory System**: Session-level persistent memory with Agent recall/memorize
- **Skill System**: Built-in presets + user-defined design Skills, self-install/uninstall by Agent
- **Canvas Workspace**: Zoomable/pannable creation canvas with drag, reference lines, and batch management
- **Relationship Graph**: Visual card reference links with trace tracking
- **RPM Rate Limiter**: Sliding window rate limiter to prevent upstream API throttling
- **Card Preview**: Image zoom, video playback, and save to gallery

### Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Hilt DI |
| Database | Room (SQLite) |
| Networking | OkHttp + Retrofit + kotlinx-serialization |
| Images | Coil |
| Video | ExoPlayer |
| Sandbox | PRoot (Alpine Linux) |
| Build | Gradle KTS + AGP 8.5.2 |

### Version

**v1.1.0** — Alpine sandbox, MCP plugin system, design Skill system, RPM rate limiter, multiple bug fixes

### Build

```bash
./gradlew assembleDebug
```

---

## License

Copyright (C) 2026 Tapcreator contributors.

This program is free software: you can redistribute it and/or modify it under the terms
of the **GNU General Public License as published by the Free Software Foundation, either
version 3 of the License (GPL-3.0)**, or (at your option) any later version. See
[LICENSE](LICENSE) for the full text.

This project is derived from / bundles third-party components (OpenMinis under GPL-3.0;
proot under GPL-2.0; talloc under LGPL-3.0; Alpine minirootfs aggregates) — see
[NOTICE](NOTICE.md) for attribution and license details. Corresponding Source must be
provided under GPL-3.0 when redistributing.

Commercial use requires prior written authorization.