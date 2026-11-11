# Touhou Little Maid (Not Hard) _(TLMNotHard)_

将 Touhou Little Maid 移植到 GTNH 2.9.0，保留女仆核心玩法并重做非生成式行为 AI。

**Beta**：当前公开测试版本为 `0.1.0-beta.2`（首个测试版 `0.1.0-beta.1` 已归档保留），正式稳定版仍在推进。

简体中文 | [English](README.en.md)

本仓库将 Minecraft 1.20.1 版 Touhou Little Maid 移植到 GTNH 2.9.0，同时支持客户端与独立专用服务端。
本页介绍当前测试版的玩法范围、构建方式与协作规范；完整的稳定版安装升级指南将在 `0.1.0` 正式发布前补齐。

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
源码采用现代 Java 语法编写，经 Jabel 编译为 Java 8 字节码，客户端与服务端均运行在 Java 25 上。

首版规划覆盖以下能力，各项实际完成度与验证进度见 [开发进度](PROGRESS.md)：

- 女仆本体、原版玩家交互、六格背包与双手装备、内置模型切换，以及酒狐全系列模型与贴图逐项验收。
- 可配置的自然生成与初始绑定魂符，神龛复活并保留上游生命代价。
- 偷吃与最小零食柜、睡觉与女仆床，以及贴合 GTNH 蒸汽与 LV 阶段的核心合成配方。
- 改进的非生成式行为 AI，涵盖感知记忆、指令约束、动作抢占、组件化会话扩展与失败恢复，并与原版 TLM 1.20.1 对照评测。
- 非魔法远程武器预瞄，首版支持弓箭，结合 2.9.0 实际维度重力与介质阻力补偿移动靶提前量和箭矢下坠。

首版不包含 LLM、空闲与战斗之外的工作模式、其他非核心 TLM 物品、更换主人或 YSM 深度内容联动。
GTNH 维护仓与 CropsNH 育种留作后续扩展；界面文本以简体中文为主，英文补齐。

## 安装

从 [GitHub Releases](https://github.com/tianlovo/TLMNotHard/releases/tag/0.1.0-beta.2) 下载
`tlmnh-0.1.0-beta.2.jar`，并对照随附的 `SHA256SUMS.txt` 核验摘要。
正常停止客户端和服务端，完整备份存档、两端配置及旧版 JAR 后，将同一份新 JAR 放入两端 `mods/` 目录并移出旧版 JAR。
本版架构调整、已知问题与后续待办见 [Beta.2 发布说明](docs/releases/0.1.0-beta.2.md)。
操作按键、配置说明与双端成对回滚步骤见 [使用说明](docs/USER_GUIDE.md)。
新生成的配置文件默认启用 Enhanced 行为策略；已有配置中显式指定的 Legacy 会原样保留，切换策略后需重启服务端。
Enhanced 默认启用动态索敌，非家园状态下的目标获取与保持半径默认分别为 20 格和 24 格，可在服务端配置中调整。
双端本地验证增量包工具见 [打包与更新说明](scripts/distribution/README.md)。
如需从源码自行构建，可在 Windows PowerShell 中执行：

```powershell
git clone https://github.com/tianlovo/TLMNotHard.git
Set-Location TLMNotHard
$env:VERSION = "0.1.0-beta.2"
./gradlew.bat build
```

主模组产物位于 `build/libs/`，独立开发诊断附属模组位于 `build/devtools/`（普通游玩无需安装 `tlmnhdevtools`）。
部署到独立的 2.9.0 测试服与客户端时，两端必须使用同一构建产物；正式版验收完成前，建议不要直接改动日常游玩的 Prism 实例。

### 依赖

- 目标整合包：GTNH 2.9.0，Minecraft 1.7.10，Forge 10.13.4.1614
- UniMixins：使用 2.9.0 自带版本，用于原生导航停止反馈与箭矢来源边界等 Mixin 引导
- JDK 25：启动 Gradle、运行单测与游戏；JDK 17：供 Jabel 编译输出 Java 8 字节码
- Gradle Wrapper 固定 9.7.1，GTNHGradle 固定 2.0.33，Blowdryer 固定 0.2.2
- Python 3：运行字节码、内置资产与仓库路径校验脚本；Node.js 22 及以上：运行 README 格式检查

工具链准备、模块源集划分与本机缓存配置详见 [开发说明](DEVELOPMENT.md)。

## 使用

在源码根目录执行单测、完整构建与归档校验：

```powershell
./gradlew.bat test
./gradlew.bat build
python scripts/verify_bytecode.py build/libs/tlmnh-0.1.0-beta.2.jar
npx --yes markdownlint-cli2@0.24.0
```

`build` 会一并运行 `:kernel:molang:test`、`test`、`clientTest`、`compatTest`、`devtoolsTest`、`testClientGson` 与架构边界检查。
默认版本号根据 Git 标签与提交自动推导；可通过 `VERSION` 环境变量显式指定构建版本，阶段里程碑单独打 tag。

在 IntelliJ IDEA 中直接打开源码根目录并按 Gradle 工程导入，将构建和测试统一委托给 Gradle。
项目 SDK 与 Gradle JVM 选 Java 25，语言级别设为 17，目标字节码设为 8。
专用 2.9.0 测试服与客户端的本机运行入口配置见 [IDEA 配置](DEVELOPMENT.md#intellij-idea-本机配置)。

## 开发状态

项目目前处于公开 Beta 阶段，`0.1.0-beta.2` 完成了源码分层、独立 `:kernel:molang` 子模块、`client` / `compat` 源集物理隔离与
`tlmnhdevtools` 开发工具拆包，首个稳定版剩余生存适配与发布验收仍在继续推进。
各模块实现进度、实机验证记录与待决问题统一维护在 [开发进度](PROGRESS.md)，每次验证均绑定具体源码提交与产物摘要。

更多实现背景与实验细节见 [开发说明](DEVELOPMENT.md)、[移植记录](SOURCE_PROVENANCE.md) 和
[动画对照实验](tools/animation-reference-1.20.1/DESIGN.md)。

## 维护者

[tianlovo](https://github.com/tianlovo)，邮箱：<tianluoqaq@gmail.com>

## 参与贡献

Bug 反馈与想法建议欢迎提交到 [Issues](https://github.com/tianlovo/TLMNotHard/issues)，也欢迎直接发 PR。
涉及玩法机制或跨模块结构调整时建议先开 Issue 对齐范围，并附上复现步骤、验证方式与 2.9.0 环境信息。

新增自有代码放在 `com.tianluoqaq.tlmnh` 包下，第三方可选模组适配统一归入 `src/compat` 或 `src/compatClient` 并经领域 SPI 暴露；
移植的上游文件保留原包名、版权头与适用许可。
提交说明采用简体中文约定式提交格式，行文保持自然简洁；中文代码注释仅省略同行末句句号，同行前句与文档正文正常加句号。
提交前请跑通 `build`、仓库路径检查与 README 格式检查，保持中英文 README 的范围与命令同步。
功能或验证状态变更时请在同批次更新 `PROGRESS.md`；IDE 配置、整合包目录、存档、日志与缓存不纳入版本控制。

## 许可证

本项目中由 tianlovo 与 contributors 原创且享有许可权的适配代码采用 GNU GPL 第 3 版，仅限该版本（`GPL-3.0-only`）。
Copyright (c) 2026 tianlovo and contributors

许可全文见 [LICENSE](LICENSE)，各文件原有的来源、作者与单独许可声明继续有效。
移植的上游代码保留 MIT 等原有许可声明；模型、贴图、动画及相关资产保留 CC BY-NC-SA 4.0 许可与原作者署名。
由原资产脚本改写的 `BuiltinBedrockAnimations.java` 继续按 CC BY-NC-SA 4.0 提供。
配音与基础音效继续遵循各自原作者条款，详细范围、权利人与许可全文见 [第三方来源与许可](THIRD_PARTY_NOTICES.md) 和 [许可目录](licenses/)。
