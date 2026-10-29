# 第三方来源与许可

当前接入构建模板、TLM 实体核心状态和内置女仆模型资源，渲染与动画运行仍在开发

| 内容 | 固定来源 | 许可与保留内容 |
| --- | --- | --- |
| GTNH 构建定义和 Wrapper 配置 | GTNewHorizons/ExampleMod1.7.10，bf0843f5b29153ad76866d8390916b2e5b559e05 | MIT，原文见 licenses/ExampleMod1.7.10-MIT.txt |
| Gradle Wrapper 脚本 | 同一模板内 gradlew 与 gradlew.bat | 保留原 Apache-2.0 许可头 |
| 构建共享规范 | ExampleMod Blowdryer 0.2.2 | 由固定模板规范提供，不将研究克隆提交进本仓库 |
| EntityMaid、MaidSchedule 的核心状态与 ID | TartaricAcid/TouhouLittleMaid，8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6 | MIT，原文见 licenses/TouhouLittleMaid-MIT.txt，保留原包名；改动见 SOURCE_PROVENANCE.md |
| 六个内置女仆模型包的模型、纹理、动画文件、图标、原始说明与语言资源 | 同一 TLM 提交的 src/main/resources/assets/touhou_little_maid/tlm_custom_pack/touhou_little_maid-1.0.0 及默认 maid.animation.json | CC BY-NC-SA 4.0，原文见 licenses/TouhouLittleMaid-CC-BY-NC-SA-4.0.txt，Copyright (c) 2019-2025 tartaric_acid；各模型作者和说明保留在原 maid_model.json 与 lang/*.json |

资产来源为 [Touhou Little Maid 固定提交](https://github.com/TartaricAcid/TouhouLittleMaid/tree/8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6)，许可为 [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/)，要求署名、非商业使用和相同方式共享；不以代码的 MIT 许可替代资产许可

本次展开上游内置包的目录，将原字节资源放入对应 assets 命名空间，没有修改模型、贴图、动画和上游说明文本；生成了本工程的装饰 ID 索引与逐文件来源摘要，位于 assets/tlmnh/builtin-models.json 和 builtin-assets-provenance.json，摘要包含原路径、SHA-256 和固定提交，目录与索引适配涉及的资产贡献同样按 CC BY-NC-SA 4.0 提供

TLM 原有代码和资产在导入时分别登记提交、原路径、修改原因及许可，不以代码许可覆盖模型和声音

Ancient Warfare 2、Zombie Awareness 与 SmartBrainLib 当前仅作为研究参考，没有复制其实现或引入运行依赖

原创代码的最终分发许可在正式版发布前单独确定，不从本表推断整个项目采用第三方模板的许可
