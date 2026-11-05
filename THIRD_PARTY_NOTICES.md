# 第三方来源与许可

当前包含构建模板、TLM 实体核心状态、Bedrock/Gecko 内置模型渲染与动画运行，以及内置女仆资源

| 内容 | 固定来源 | 许可与保留内容 |
| --- | --- | --- |
| GTNH 构建定义和 Wrapper 配置 | GTNewHorizons/ExampleMod1.7.10，bf0843f5b29153ad76866d8390916b2e5b559e05 | MIT，原文见 licenses/ExampleMod1.7.10-MIT.txt |
| Gradle Wrapper 脚本 | 同一模板内 gradlew 与 gradlew.bat | 保留原 Apache-2.0 许可头 |
| 构建共享规范 | ExampleMod Blowdryer 0.2.2 | 由固定模板规范提供，不将研究克隆提交进本仓库 |
| EntityMaid、MaidSchedule 的核心状态与 ID | TartaricAcid/TouhouLittleMaid，8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6 | MIT，原文见 licenses/TouhouLittleMaid-MIT.txt，保留原包名；改动见 SOURCE_PROVENANCE.md |
| simplebedrockmodel 的模型 POJO、几何构造与顶点生成 | 同一 TLM 提交的 com/github/tartaricacid/simplebedrockmodel/client/bedrock | MIT，保留原包名与来源头；1.7.10 适配见 SOURCE_PROVENANCE.md |
| TLM 内置 Bedrock 动画、骨骼包装与注册表 | 同一 TLM 提交的 api/animation、client/animation/inner 与 client/animation/script | MIT，保留原文件包名，改动与保留范围见 SOURCE_PROVENANCE.md |
| TLM 内嵌 Molang 的词法、解析与表达式求值核心 | 同一 TLM 提交的 com/github/tartaricacid/touhoulittlemaid/molang | 原 Molang 引擎为 MIT，Copyright (c) 2021-2023 Unnamed Team，原文见 licenses/UnnamedTeam-Molang-MIT.txt；保留各文件原许可头及 TLM 包名 |
| Molang 数学、变量绑定、存储、值包装和插值辅助 | 同一 TLM 提交的 geckolib3/core/molang 与 util/ 下48个原文件 | MIT，保留 TLM 原包名及逐文件来源头，Java8 与纯数据上下文适配见 SOURCE_PROVENANCE.md；不将它们与 Unnamed Team 原引擎的来源混淆 |
| Molang 主绑定、解析包装和帧查询 | 同一 TLM 提交的 geckolib3/core/molang 下8个原文件与 core/controller/AnimationControllerContext.java | MIT，保留原包名；游戏对象查询改接 1.7.10 实体的纯数据帧，并接入内置模型的逐帧查询、动画控制器与时间轴，详见 SOURCE_PROVENANCE.md |
| TLM 内嵌 GeckoLib 3 的模型解析、网格与骨骼实例 | 同一 TLM 提交的 com/github/tartaricacid/touhoulittlemaid/geckolib3；GeckoLib 原许可核对提交 4533673f89c2f1301ba0676ab7240ed20fb7f784 | MIT，同时保留 licenses/GeckoLib-MIT.txt 的 Copyright (c) 2021 GeckoThePecko、上游文件中的 Bernie G. 作者头与 TLM 许可；内置模型的几何、骨骼、关键帧与动画控制器运行已接入，改动见 SOURCE_PROVENANCE.md |
| Gecko 关键帧、插值、动画文件和时间轴指令 | 同一 TLM 提交的 geckolib3 下18个原文件 | MIT，保留 Gecko 作者头、原包名和两份许可，集合、严格表达式解析及默认长度修正见 SOURCE_PROVENANCE.md |
| TLM 内置动画的 tlm/ysm 查询、骨骼结构体与二阶物理 | 同一 TLM 提交的 client/animation/gecko/molang 下10个原文件 | MIT，保留原包名及 SecondOrder 的 MicroCraft 作者信息；只接内置资源所需查询，不集成外部 YSM 模组，边界适配见 SOURCE_PROVENANCE.md |
| JOML Java 数学类 | JOML-CI/JOML，1.10.8，Maven org.joml:joml:1.10.8 | MIT，Copyright (c) 2015-2024 JOML，原文见 licenses/JOML-MIT.txt；打包时重定位，不包含未使用的 Kotlin 扩展与运行库 |
| Gecko 层级遍历使用的 fastutil 容器 | vigna/fastutil，it.unimi.dsi:fastutil:8.5.18 | Apache-2.0，Sebastiano Vigna；运行时复用 RC2 已提供的库，开发与测试单独解析，不随本模组 JAR 打包或重定位 |
| Battlegear2-for-Backhand 盾牌公开 API | GTNewHorizons/Battlegear2-for-Backhand，1.6.8-backhand | 上游 LICENCE 为 GNU GPL v3；仅 compileOnly API 依赖，运行时复用 RC2 已安装模组，不随本模组打包或复制实现类；平台适配范围见 SOURCE_PROVENANCE.md |
| GregTech配方与核心物品公开API | GTNewHorizons/GT5-Unofficial，5.09.54.265，bb00f3f1023e20d3cce84cf09abb442d22a3a5a5 | 上游[LICENSE.txt](https://github.com/GTNewHorizons/GT5-Unofficial/blob/bb00f3f1023e20d3cce84cf09abb442d22a3a5a5/LICENSE.txt)为LGPL v3；仅非传递compileOnly依赖，运行时复用整包提供方，不打包其实现 |
| 六个内置女仆模型包的模型、纹理、动画文件、图标、原始说明与语言资源 | 同一 TLM 提交的 src/main/resources/assets/touhou_little_maid/tlm_custom_pack/touhou_little_maid-1.0.0 及默认 maid.animation.json | CC BY-NC-SA 4.0，原文见 licenses/TouhouLittleMaid-CC-BY-NC-SA-4.0.txt，Copyright (c) 2019-2025 tartaric_acid；各模型作者和说明保留在原 maid_model.json 与 lang/*.json |

资产来源为 [Touhou Little Maid 固定提交](https://github.com/TartaricAcid/TouhouLittleMaid/tree/8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6)，
许可为 [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/)，要求署名、非商业使用和相同方式共享；
不以代码的 MIT 许可替代资产许可

本次展开上游内置包的目录，将原字节资源放入对应 assets 命名空间，没有修改模型、贴图、动画和上游说明文本；
生成了本工程的装饰 ID 索引与逐文件来源摘要，位于 assets/tlmnh/builtin-models.json 和 builtin-assets-provenance.json，
摘要包含原路径、SHA-256 和固定提交，目录与索引适配涉及的资产贡献同样按 CC BY-NC-SA 4.0 提供

BuiltinBedrockAnimations.java 含两个原资产脚本 arm.js 和 float.js 的 Java 改写，原包 authors_and_credits 标注作者 TartaricAcid；
该文件按 CC BY-NC-SA 4.0 提供，修改内容为去除已弃用分支并接入本工程逐帧状态与矩阵；原始脚本与作者说明仍保留，
完整来源见 SOURCE_PROVENANCE.md 和资源来源清单

TLM 原有代码和资产在导入时分别登记提交、原路径、修改原因及许可，不以代码许可覆盖模型和声音

Ancient Warfare 2、Zombie Awareness 与 SmartBrainLib 当前仅作为研究参考，没有复制其实现或引入运行依赖

本项目中由 tianlovo 与 contributors 原创且有权许可的适配代码采用 GNU GPL 第 3 版，仅限该版本

TLMNH original code license: GPL-3.0-only
Copyright (c) 2026 tianlovo and contributors

许可全文见 [LICENSE](LICENSE)，本表所列上游作者、原许可及各文件单独声明继续适用
`BuiltinBedrockAnimations.java` 的资产脚本改写部分仍按上述 CC BY-NC-SA 4.0 提供，声音继续遵循各原作者条款

盾牌 API 来源见 [Battlegear2-for-Backhand](https://github.com/GTNewHorizons/Battlegear2-for-Backhand)，原许可见其 [LICENCE](https://github.com/GTNewHorizons/Battlegear2-for-Backhand/blob/1.6.8-backhand/LICENCE)
TConstruct 与 TinkersDefense 的开发夹具调用目标环境现有工厂和物品，不将这些模组或其实现类纳入分发 JAR

## 内置配音的单独来源

- LittleMaid PECO：作品 LittleMaid_PEKO/PECO，CV 秋野かえで，作者 Tamemaru，来自[作者发布页](https://booth.pm/ja/items/1903163)
  页面列出 Minecraft 作品及内嵌配音的自制 MOD 用途，同时限制无授权转载与单独再分发；不能把这些声音改标为 MIT 或 CC
- 默认音包：Voice: Amitaro's Voice Material Studio ([あみたろの声素材工房](https://amitaro.net/))
  核对日 2026-11-01，[英文条款](https://amitaro.net/voice/terms/)限制用户可独立提取/下载的打包形式
  [日文条款](https://amitaro.net/voice/voice_rule/)另列 MOD 同捆和事后报告要求
  且明确两版适用条件不同

两包435个配音文件及固定上游另外10个基础音效以原字节保留在源码资源中
原作者说明、来源、逐文件摘要和事件映射随资源保留，正式发布前的署名、说明和报告事项继续按原条款核对

## 普通气泡素材

普通气泡代码来自固定 TLM 提交，沿用 MIT 许可及原包名
内置 20 张表情 PNG、type2 背景与颜文字数据来自同一提交的 assets/data，按上游素材 CC-BY-NC-SA-4.0 保留原字节，作者署名与许可见上述 TLM 素材条目
逐文件路径与 SHA-256 记录在 gui-assets-provenance.json，未加入外部图片包或生成式 AI 服务

## 内置补充资源与legacy保留

191增加固定TLM提交中的332个原始资源文件，包含额外模型、贴图、动画、语言与包元数据，共5,538,204字节
保留原包作者署名和说明，沿用各源文件的上游许可信息及本仓库已有许可原文，逐文件来源与SHA-256见assets/tlmnh/builtin-retention-provenance.json

原包pack.mcmeta及pack.png保存在assets/tlmnh/retained-builtin-pack，47个legacy文件保留原子目录
资源保留范围与首版运行时功能接线分别登记，详细映射及导入依据见SOURCE_PROVENANCE.md
