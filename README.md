# Touhou Little Maid (Not Hard) _(TLMNotHard)_

将 Touhou Little Maid 移植到 GTNH 2.9.0，保留女仆核心玩法并重做非生成式行为 AI。

**0.1.0 发布准备**：本页介绍首个稳定版的功能范围。当前公开版本为 `0.1.0-beta.2`，正式版产物与最终验收记录将在发布时补齐。

简体中文 | [English](README.en.md)

本仓库将 Minecraft 1.20.1 版 Touhou Little Maid 移植到 GTNH 2.9.0，同时支持客户端与独立专用服务端。
玩法、装备和工作模式的操作步骤见 [使用说明](docs/USER_GUIDE.md)，本轮变更见 [0.1.0 发布说明](docs/releases/0.1.0.md)。

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

首版包含以下玩法：

- 女仆获取与首次绑定、原版玩家交互、神龛复活、睡觉与女仆床、偷吃与最小零食柜，以及蒸汽和 LV 阶段配方。
- 默认开放全部 36 格背包、独立双手和护甲、按好感分级开放的 30 格饰品栏，以及五类携带背包的优先收纳与工作取用。
- 普通 GregTech 维护仓巡检、铃铛绑定和范围预览，以及 CropsNH 真实世界育种、目标作物与变异池、属性提纯和缺件提醒。
- 真实饥饿、食物与饱和度恢复、治愈之斧、热物品灼伤与丢弃，以及飞行装备、特殊护甲、供氧、隔热和坠落自救适配。
- 默认 Enhanced 非生成式行为 AI、统一战斗和弓箭预瞄，按实际维度重力、介质阻力与目标移动补偿弹道；已有显式 Legacy 配置继续兼容。
- 254 项内置模型选择、酒狐 21 个基础条目与 14 个额外纹理选择、435 个配音文件和 10 个基础音效。

外部模型导入、YSM 联动、LLM 和更换主人不在本版范围内。各功能实测与未决验收见 [开发进度](PROGRESS.md)。

## 安装

正式版发布后，从 [GitHub Releases](https://github.com/tianlovo/TLMNotHard/releases) 下载 `tlmnh-0.1.0.jar`，
并对照同一发布页的 `SHA256SUMS.txt` 核验摘要。发布前的公开下载仍以 `0.1.0-beta.2` 为准。
正常停止客户端和服务端，备份完整存档、两端配置及旧 JAR，再将同一份新 JAR 放入两端 `mods/` 并移出旧版。
普通游玩安装主包即可，开发诊断附属模组 `tlmnhdevtools` 供开发与验证使用。

操作、服务端配置与成对回滚步骤见 [使用说明](docs/USER_GUIDE.md)。新配置默认使用 `enhanced`，
已有显式 `legacy_prototype` 设置继续生效；修改服务端策略后重启服务端。
Enhanced 的非家园目标获取、保持半径默认分别为 20 格、24 格，实际行为还受主人活动域、视线和预算限制。
本地验证增量包工具见 [打包与更新说明](scripts/distribution/README.md)。

从源码构建时，在 Windows PowerShell 中执行：

```powershell
git clone https://github.com/tianlovo/TLMNotHard.git
Set-Location TLMNotHard
$env:VERSION = "0.1.0"
./gradlew.bat build
```

主模组产物位于 `build/libs/`，独立开发工具位于 `build/devtools/`。客户端与服务端使用同一构建产物。

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
python scripts/verify_bytecode.py build/libs/tlmnh-0.1.0.jar
npx --yes markdownlint-cli2@0.24.0
```

`build` 会一并运行 `:kernel:molang:test`、`test`、`clientTest`、`compatTest`、`devtoolsTest`、`testClientGson` 与架构边界检查。
默认版本号根据 Git 标签与提交自动推导；可通过 `VERSION` 环境变量显式指定构建版本，阶段里程碑单独打 tag。

在 IntelliJ IDEA 中直接打开源码根目录并按 Gradle 工程导入，将构建和测试统一委托给 Gradle。
项目 SDK 与 Gradle JVM 选 Java 25，语言级别设为 17，目标字节码设为 8。
专用 2.9.0 测试服与客户端的本机运行入口配置见 [IDEA 配置](DEVELOPMENT.md#intellij-idea-本机配置)。

## 开发状态

`0.1.0` 在 Beta.2 分层架构上加入完整背包、饰品、两类工作模式与生存装备适配。
正式发布前仍需完成最终统计、跨模块组合、双端联机与玩家终验，并登记最终提交、产物摘要和 CI 结果。
当前公开版本、已通过项目及保留失败以 [开发进度](PROGRESS.md) 和 [发布说明](docs/releases/0.1.0.md) 为准。

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

Voice: Amitaro's Voice Material Studio (<https://amitaro.net/>).
LittleMaid_PEKO/PECO: CV 秋野かえで, Tamemaru (<https://booth.pm/ja/items/1903163>).
