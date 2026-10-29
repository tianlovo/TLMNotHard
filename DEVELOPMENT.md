# 开发与验证

当前为 indev，构建通过和入口加载不代表女仆玩法可用，模型、完整交互、行为和原版 benchmark 仍在后续计划中

## 构建

JDK 25 启动 Gradle，Jabel 使用 JDK 17 / release 8，运行游戏使用 Java 25；本地需要安装相应工具链，不能将 forceToolchainVersion 改成 25 绕过 Java 8 输出要求

```powershell
./gradlew.bat spotlessApply build
python scripts/verify_bytecode.py build/libs/<候选版本>.jar
```

第一次构建会下载 Forge、MCP 和 Minecraft 原版开发依赖，网络失败应核对下载与缓存，不能关闭格式和字节码检查

## 代码职责

| 路径 | 职责 |
| --- | --- |
| com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid | 原包名实体、DataWatcher、属性与核心 NBT |
| com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule | 上游 DAY、NIGHT、ALL |
| com.tianluoqaq.tlmnh.common.inventory.MaidInventory | 六格背包与装备槽位视图，没有第二份主手或护甲数据 |
| com.tianluoqaq.tlmnh.common.registry.ModEntities | 模组内实体 ID 0，持久化名称 tlmnh.maid |
| com.tianluoqaq.tlmnh.common.command.DevelopmentCommand | 显式开发开关下的 Forge 环境检查 |

实体同步索引 20–27 分别为模型、任务、作息、经验、饥饿、好感、拾取标记和副手；主手与护甲沿用原版装备同步，副手独立保存并在数量、耐久或 NBT 原地变化后同步副本

背包视图 0–5 为背包，6 主手、7 副手、8–11 为靴子到头盔；只有六格背包计入基础容量，不能把 12 个总槽位称为十二格背包

存档扩展使用 TLMNH.Schema = 1，背包为 MaidInventory 列表，副手为 TLMNHOffhand，原有模型、任务、作息和经验等键保留；TLMNH 扩展区中的未知字段保留，不宣称支持读取高版本 Minecraft 存档

## 真实服务端状态检查

在专用测试服务器的 JVM 参数中显式加入 `-Dtlmnh.devCommands=true`，默认不注册此命令；控制台或权限等级 4 的操作者可执行：

```text
tlmnhdev state-test
```

它构造不加入世界的临时实体，检查首次绑定、拒绝重复绑定、全部 12 个槽位、名称、生命、坐姿、任务、作息、生活数值和拾取开关的 NBT 往返，并检查副手原地变化和损坏枚举的回退；成功标记为 TLMNH_STATE_TEST_PASS

该检查不会给玩家发物品，也没有验证区块保存、服务端重启、容器交易或客户端观察者；后续必须分别验证这些流程，不能将本标记当作完整 M1 或首版验收

## 维护边界

上游来源与许可见 [第三方说明](THIRD_PARTY_NOTICES.md) 和 [移植记录](SOURCE_PROVENANCE.md)，复制原文件时保留包名，独立适配文件使用 com.tianluoqaq.tlmnh

修改存档、槽位、实体 ID 或网络字段需要记录迁移方式；公共侧不得引用客户端渲染类，所有主人操作仍由服务端复核权限和当前状态
