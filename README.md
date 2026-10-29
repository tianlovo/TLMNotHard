# Touhou Little Maid (Not Hard)

**indev — 正在开发，尚无正式版本；本次初始化提交尚不包含可运行模组**

将 Touhou Little Maid 的 Minecraft 1.20.1 版本移植到 GTNH 2.9.0 RC2，首版保留女仆本体、陪伴、战斗和生活行为，支持客户端与独立服务端

- Minecraft 1.7.10，Forge 10.13.4.1614，运行环境 Java 25
- 使用现代 Java 语法，目标为 Java 8 字节码，优先采用 GTNH 官方模板的 Jabel 方案
- 构建模板：[GTNH ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10)
- 上游：[Touhou Little Maid 的 1.20 分支](https://github.com/TartaricAcid/TouhouLittleMaid/tree/1.20)，其目标 Minecraft 版本为 1.20.1
- 首版暂不包含 LLM、非核心附属物品、空闲和战斗之外的工作，以及 YSM 模型替换
- 保留内置模型切换，酒狐全系列必须可用，保留原版玩家交互与双手状态
- 保留可配置自然生成及初始绑定魂符、神龛与原生命代价、偷吃和最小零食柜、睡觉和女仆床
- 改进行为 AI，覆盖感知记忆、指令约束、动作中断和失败恢复，并与原版 TLM 1.20.1 做对照测评
- 非魔法远程攻击加入预瞄，首版弓箭使用 RC2 实际重力与阻力，同时补偿目标移动和下坠
- 不更换主人，不移植其他内容模组联动；维护仓和 CropsNH 育种保留为未来扩展方向
- 简体中文优先，英文为副语言

`src/` 当前仅作目录占位，没有模组源码；后续开始实现时再引入模板构建文件和移植内容

从上游移植的文件保留其包名、来源及适用许可，新增独立适配文件使用 `com.tianluoqaq.tlmnh` 根包名

上游代码、模型、贴图与声音的许可分别处理，本仓库目前没有引入这些内容

维护者：tianlovo，联系邮箱：tianluoqaq@gmail.com

English: An indev port of Touhou Little Maid from Minecraft 1.20.1 to GTNH 2.9.0 RC2, with improved behaviour AI and dedicated-server support. No playable build yet. Modern Java syntax, Java 8 bytecode, Java 25 runtime

