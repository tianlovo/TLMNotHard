# Touhou Little Maid (Not Hard) _(TLMNotHard)_

将 Touhou Little Maid 移植到 GTNH 2.9.0，保留女仆核心玩法并改进行为 AI。

**Indev**：正在开发，尚无正式版本；当前构建仅用于开发和测试。

简体中文 | [English](README.en.md)

本仓库将 Minecraft 1.20.1 版 Touhou Little Maid 移植到 GTNH 2.9.0，同时支持客户端与独立服务端。
本页记录开发版的范围、进度与构建方式；完整玩法、安装与更新说明及发布验收结果将在正式版发布前补齐。

## 目录

- [背景](#背景)
- [安装](#安装)
- [使用](#使用)
- [开发状态](#开发状态)
- [维护者](#维护者)
- [参与贡献](#参与贡献)
- [许可证](#许可证)

## 背景

工程基于 [GTNH ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10) 构建模板搭建，
上游源码取自 [Touhou Little Maid 的 1.20 分支](https://github.com/TartaricAcid/TouhouLittleMaid/tree/1.20)。
源码使用现代 Java 语法并编译为 Java 8 字节码，客户端与服务端均运行在 Java 25 上。

首版计划纳入以下内容（以下为范围清单，不代表全部已完成）：

- 女仆本体、原版玩家交互、背包与双手、内置模型切换，以及酒狐全系列逐项验收。
- 可配置的自然生成与初始绑定魂符，神龛复活并保留上游生命代价。
- 偷吃与最小零食柜、睡觉与女仆床，以及融入 GTNH 科技进度的核心配方。
- 改进的非生成式行为 AI，覆盖感知记忆、指令约束、动作中断与失败恢复，并与原版 TLM 1.20.1 做对照测评。
- 非魔法远程武器预瞄，首版先支持弓箭，按 2.9.0 实际重力与空气阻力补偿目标移动和箭矢下坠。

首版不包含 LLM、空闲与战斗之外的工作、其他非核心 TLM 物品、更换主人，或 YSM 等额外内容模组联动。
GTNH 维护仓与 CropsNH 育种留作后续扩展；语言支持以简体中文为主，英文补齐。

## 安装

目前仅提供源码构建，尚未发布供生存游玩使用的正式安装包。
开发候选的玩家操作、配置项与双端成对回滚说明见 [Indev 使用说明](docs/USER_GUIDE.md)。
新配置默认启用 Enhanced；已有配置中显式指定的 Legacy 会继续保留，修改策略后需重启服务端。
当前 Enhanced 候选采用动态索敌，非家园目标获取与保持半径默认分别为 20 格和 24 格，可在服务端配置中调整。
双端增量打包工具见 [打包与更新说明](scripts/distribution/README.md)，当前仅生成本地验证包。
在 Windows PowerShell 中执行以下命令：

```powershell
git clone https://github.com/tianlovo/TLMNotHard.git
Set-Location TLMNotHard
$env:VERSION = "0.1.0-indev.local"
./gradlew.bat build
```

构建产物位于 `build/libs/`；部署到独立的 2.9.0 正式版测试客户端与服务端时，两端须使用同一候选版本。
本项目尚未完成正式发布验收，实际游玩的 Prism 实例在正式分发前应保持原样。

### 依赖

- 目标整合包：GTNH 2.9.0，Minecraft 1.7.10，Forge 10.13.4.1614
- UniMixins：使用 2.9.0 自带版本，提供原生导航停止反馈所需的 Mixin 引导
- JDK 25：启动 Gradle、运行测试与游戏；JDK 17：供 Jabel 编译，目标字节码为 Java 8
- Gradle Wrapper 固定 9.7.1，GTNHGradle 固定 2.0.33，Blowdryer 固定 0.2.2
- Python 3：运行独立校验脚本；Node.js 22 或更高版本：运行 README 格式检查

工具链选择、首次依赖下载与本机缓存说明见 [开发说明](DEVELOPMENT.md)。

## 使用

在源码根目录运行构建与校验：

```powershell
./gradlew.bat test
./gradlew.bat build
python scripts/verify_bytecode.py build/libs/tlmnh-0.1.0-indev.local.jar
npx --yes markdownlint-cli2@0.24.0
```

上例沿用安装步骤中的 `VERSION`；若使用其他版本名，请同步替换 JAR 文件名。
默认版本号由 Git 标签与提交自动生成，正式版发布前保留 `indev` 标识，普通提交不单独打 tag。

在 IntelliJ IDEA 中直接打开源码根目录并按 Gradle 工程导入，将构建和测试委托给 Gradle。
项目 SDK 与 Gradle JVM 使用 Java 25，源码语言级别设为 17，目标字节码设为 8。
本机专用 2.9.0 正式版测试服与客户端的运行入口见 [IDEA 配置](DEVELOPMENT.md#intellij-idea-本机配置)。

## 开发状态

当前仍处于 Indev 阶段，尚未满足首个正式版的发布门槛。
首版整体进度、各模块实现与验证详情、近期进展及下一步计划统一记录在 [Indev 开发进度](PROGRESS.md)。
构建检查、夹具测试、自然玩法验证与正式发布验收分别记录，不合并不同候选的结果来宣称单一产物完整通过。

更多技术细节见 [开发说明](DEVELOPMENT.md)、[移植记录](SOURCE_PROVENANCE.md) 和
[动画对照实验](tools/animation-reference-1.20.1/DESIGN.md)。

## 维护者

[tianlovo](https://github.com/tianlovo)，邮箱：<tianluoqaq@gmail.com>

## 参与贡献

问题反馈与功能建议请提交至 [Issues](https://github.com/tianlovo/TLMNotHard/issues)，欢迎提交 PR。
涉及较大的玩法或架构改动请先讨论范围，并附上复现条件、验证方式及所用 2.9.0 环境说明。

新增独立适配文件使用 `com.tianluoqaq.tlmnh` 根包名；移植的上游文件保留原包名、来源信息与适用许可。
提交说明遵循简体中文约定式提交规范，新增中文表述保持自然、准确；中文代码注释仅省略同行末句句号，同行前句与文档正文正常使用句号。
提交前请运行 `build` 与 README 格式检查，保持双语 README 的范围、进度与命令一致。
实现或验证状态发生变化时，需在同次提交中更新 `PROGRESS.md`；进度文档在本地检查，不纳入文档 CI 的触发或 lint 范围。
IDE 配置、整合包文件、存档、日志、缓存与构建产物不纳入版本管理。

## 许可证

本项目中由 tianlovo 与 contributors 原创且有权许可的适配代码采用 GNU GPL 第 3 版，仅限该版本（`GPL-3.0-only`）。
Copyright (c) 2026 tianlovo and contributors

许可全文见 [LICENSE](LICENSE)，各文件已有的来源、作者及单独许可声明继续适用。
移植的上游代码保留 MIT 等原有许可声明；模型、贴图、动画及相关资产保留 CC BY-NC-SA 4.0 许可与原作者署名。
由原资产脚本改写的 `BuiltinBedrockAnimations.java` 继续按 CC BY-NC-SA 4.0 提供。
配音与基础音效继续遵循各自原作者条款，具体范围、权利人与许可原文见 [第三方来源与许可](THIRD_PARTY_NOTICES.md) 和 [许可目录](licenses/)。
