# Touhou Little Maid (Not Hard) _(TLMNotHard)_

将 Touhou Little Maid 移植到 GTNH 2.9.0 RC2，保留女仆核心玩法，改进行为 AI

**Indev**：正在开发，尚无正式版本，当前构建用于开发和测试

简体中文 | [English](README.en.md)

本仓库是 Minecraft 1.20.1 版 Touhou Little Maid 的移植工程，目标为 GTNH 2.9.0 RC2 的客户端与独立服务端
本页记录开发版的范围、进度和构建方式，完整玩法、安装更新说明及发布验收结果将在正式版发布前补齐

## 目录

- [背景](#背景)
- [安装](#安装)
- [使用](#使用)
- [开发状态](#开发状态)
- [维护者](#维护者)
- [参与贡献](#参与贡献)
- [许可证](#许可证)

## 背景

以 [GTNH ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10) 为构建模板，
上游为 [Touhou Little Maid 的 1.20 分支](https://github.com/TartaricAcid/TouhouLittleMaid/tree/1.20)
使用现代 Java 语法，输出 Java 8 字节码，两端在 Java 25 上运行

首版计划保留以下内容，这份清单不表示全部已经实现：

- 女仆本体、原版玩家交互、背包与双手，内置模型切换，酒狐全系列逐项验收
- 可配置自然生成及初始绑定魂符，神龛复活并保留上游生命代价
- 偷吃与最小零食柜，睡觉与女仆床，核心配方融入 GTNH 科技进度
- 改进非生成式行为 AI，覆盖感知记忆、指令约束、动作中断和失败恢复，与原版 TLM 1.20.1 做对照测评
- 非魔法远程预瞄，首版先支持弓箭，按 RC2 实际重力与阻力补偿目标移动和下坠

首版不包含 LLM、空闲和战斗之外的工作、其他非核心 TLM 物品、换主人或 YSM 等额外内容模组联动
GTNH 维护仓和 CropsNH 育种留作后续扩展，简体中文优先，英文补齐

## 安装

目前提供源码构建，尚未发布供生存游玩使用的正式安装包
以下命令在 Windows PowerShell 中执行：

```powershell
git clone https://github.com/tianlovo/TLMNotHard.git
Set-Location TLMNotHard
$env:VERSION = "0.1.0-indev.local"
./gradlew.bat build
```

构建产物位于 `build/libs/`；部署到独立的 RC2 测试客户端和服务端时，使用同一候选版本
本项目尚未完成正式发布验收，实际游玩的 Prism 实例在正式分发前保持原样

### 依赖

- 目标整合包：GTNH 2.9.0 RC2，Minecraft 1.7.10，Forge 10.13.4.1614
- JDK 25：启动 Gradle、运行测试及游戏；JDK 17：供 Jabel 编译，目标字节码为 Java 8
- Gradle Wrapper 固定 9.7.1，GTNHGradle 固定 2.0.33，Blowdryer 固定 0.2.2
- Python 3：运行独立校验脚本；Node.js 22 或更新版本：运行 README 格式检查

工具链选择、首次依赖下载和本机缓存说明见 [开发说明](DEVELOPMENT.md)

## 使用

在源码根目录运行构建和验证：

```powershell
./gradlew.bat test
./gradlew.bat build
python scripts/verify_bytecode.py build/libs/tlmnh-0.1.0-indev.local.jar
npx --yes markdownlint-cli2@0.24.0
```

上例沿用安装步骤中的 `VERSION`；若使用其他版本名，应替换 JAR 文件名
默认版本由 Git 标签和提交生成，正式版前保留 `indev`；普通提交不单独打 tag

IntelliJ IDEA 请打开源码根目录，以 Gradle 工程导入，构建和测试委托给 Gradle
项目 SDK 与 Gradle JVM 使用 Java 25，源码语言级别为 17，目标字节码为 8
本机专用 RC2 测试服和客户端的运行入口见 [IDEA 配置](DEVELOPMENT.md#intellij-idea-本机配置)

## 开发状态

已通过的检查和仍待完成的内容分别记录，构建成功不代表玩法验收完成

| 范围 | 当前状态 |
| --- | --- |
| 构建与加载 | Java 25 构建、Java 8 字节码检查通过，构建入口已有 RC2 双端加载记录 |
| 实体基础 | 核心状态、六格背包、双手保存、重启恢复和容器权限已有服务端夹具验证 |
| 基础交互 | 驯服、坐姿、鞍背负、金苹果、药水、牛奶、12XP换瓶和命名牌已有真实服务端夹具；命名窗口已接入，真实客户端提交与完整网络点击待验收 |
| 模型与动画 | 已接入内置几何、Bedrock 姿态和 Gecko 核心动作播放；CPU 检查通过，游戏画面待验收 |
| 内置模型选择 | 已接入搜索、分页、酒狐筛选和额外贴图选择；真实 RC2 中 254 项目录可达，35 酒狐选择经客户端与服务端往返核对 |
| 动画回归 | 两模型三合同与原版CPU回放逐值一致，冻结包在独立干净克隆的Gradle离线模式下通过6正例与7个Java负例；实际头角绑定及无光影FBO矩阵故障检查通过，完整世界渲染待验 |
| 核心生活与获取 | 已接入初始魂符、神龛、死亡保管、零食柜偷吃和女仆床；当前为Indev实现，真实运行、重启、最终外观和GTNH配方待验 |
| 待完成 | 动作补充与可视化验收、装备层、完整生活与战斗、玩家交互验收 |
| 待验收 | 酒狐和其余模型的实际画面、动画转换、Angelica 兼容、多人同步、完整玩法 |
| 行为 AI 与预瞄 | 单入口跟随、坐下和背负中断已有自然tick实测，近战核心与实际箭物理夹具通过；已接入弓箭拉弓、运动与下坠补偿，新增远程执行待真实运行，完整战斗生活与原版benchmark未完成 |

资源摘要、CPU 几何和姿态检查不能代替游戏内的渲染与动画验收
真实空气虚空展台和旁观观察者已在 RC2 双端运行；逐模型动作循环、暂停与完整视觉矩阵仍待补齐
开发细节与文件来源见 [开发说明](DEVELOPMENT.md)、[移植记录](SOURCE_PROVENANCE.md)
基础跟随实测只覆盖固定平坦展台，不能据此宣称复杂寻路或智能程度达到原版
动画对照的输入合同、证据边界和运行方式见 [对照实验](tools/animation-reference-1.20.1/DESIGN.md)
冻结CPU回归需要已准备的Gradle依赖缓存；无光影FBO实验只覆盖两个当前姿态，不代表35个酒狐、全部动作、世界矩阵或光影兼容已经验收

## 维护者

[tianlovo](https://github.com/tianlovo)，邮箱：<tianluoqaq@gmail.com>

## 参与贡献

问题和建议请提交到 [Issues](https://github.com/tianlovo/TLMNotHard/issues)，欢迎提交 PR
较大的玩法或架构改动请先讨论范围，并附复现条件、验证方式及所用 RC2 环境

新增独立适配文件使用 `com.tianluoqaq.tlmnh` 根包名；移植的上游文件保留原包名、来源与适用许可
提交使用简体中文约定式提交，新增中文说明保持自然、准确，中文句尾不加句号
提交前运行 `build` 与 README 格式检查，双语 README 的进度、范围和命令须一致
IDE 设置、整合包、存档、日志、缓存与构建产物不进入仓库

## 许可证

新增原创代码的最终分发许可尚未确定（`UNLICENSED`），作者为 tianlovo
已移植的上游代码分别保留其 MIT 等许可；模型、贴图、动画及相关资产使用 CC BY-NC-SA 4.0，保留原作者署名
具体适用范围、权利人与许可原文见 [第三方来源与许可](THIRD_PARTY_NOTICES.md) 和 [许可目录](licenses/)
不能以模板或代码的 MIT 许可概括整个模组及其资产
