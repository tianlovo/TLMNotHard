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
| com.tianluoqaq.tlmnh.common.inventory.ContainerMaid | 容器槽位、物品转移和主人操作的服务端复核 |
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

区块保存与服务端重启使用独立夹具，只在专用测试存档执行：

```text
tlmnhdev persist-create
save-all
stop
```

重启同一存档、同一候选 JAR 的服务器后执行 `tlmnhdev persist-check`，检查带原 UUID 的唯一女仆、12 个槽位、装备耐久与 NBT、核心状态和扩展区；期望值保存在独立的 WorldSavedData，夹具实体按普通区块流程保存

成功标记为 TLMNH_PERSISTENCE_CHECK_PASS；测试报告同时保存两次 Java 进程、停服和启动日志，单次进程内执行 create/check 不能作为重启证据；夹具无敌且静坐，不能用于战斗或生存玩法验收

容器夹具使用 `tlmnhdev container-test`，在真实服务端构造两名临时 FakePlayer 和一只临时女仆，检查堆叠与满格守恒、护甲有效性、非主人拒绝、距离与实体身份撤权，以及设置操作的逐次校验；结束时清理夹具物品和实体

成功标记为 TLMNH_CONTAINER_TEST_PASS，不表示已经验收客户端界面、实际网络点击或多人观察；按钮复用原版 C11 容器消息，动作编号限制在 0–8，服务端不会信任客户端的槽位、所有权或距离判断

## 主人界面与基础交互

野生女仆使用蛋糕首次绑定，生存消耗一个蛋糕，创造不消耗；已绑定女仆不接受其他玩家的蛋糕，交互不提供换主人入口

主人普通右键打开六格背包、双手和护甲界面，可以设置任务、作息、拾取与坐姿；潜行空手右键切换坐姿，坐下时清除当前目标与路径；当前尚未实现模型、行为和其余物品交互，选择战斗任务不代表已经能够作战

CommonProxy 只创建服务端容器，客户端类型位于 ClientProxy 和 GuiMaid；开发开关开启时，`tlmnhdev spawn [玩家]` 在在线玩家附近生成一只绑定测试女仆

`tlmnhdev interaction-test` 使用未加入在线列表的服务器玩家检查蛋糕消耗、首次绑定、拒绝换主人、坐姿和超距拒绝，成功标记为 TLMNH_INTERACTION_TEST_PASS；它不发送真实玩家网络点击，也不打开客户端界面

## 维护边界

内置模型资源使用 `scripts/import_builtin_assets.py --upstream <固定 TLM 克隆路径>` 静态核对，显式添加 `--write` 才导入；脚本要求固定提交且相关上游文件没有修改，保留原字节与原始作者说明，生成模型装饰 ID 和逐文件来源摘要，不导入椅子或 LLM 设置

使用 `python scripts/verify_builtin_assets.py [候选JAR]` 核对源码或打包后资源摘要及引用；`tlmnhdev model-test` 在服务端检查 254 个装饰 ID 的 NBT 往返、酒狐 19 个 Gecko 基础条目与 2 个 Bedrock 基础条目合计 35 个选择，以及未知模型回退；上述检查均不能替代客户端渲染和动画验收

上游来源与许可见 [第三方说明](THIRD_PARTY_NOTICES.md) 和 [移植记录](SOURCE_PROVENANCE.md)，复制原文件时保留包名，独立适配文件使用 com.tianluoqaq.tlmnh

修改存档、槽位、实体 ID 或网络字段需要记录迁移方式；公共侧不得引用客户端渲染类，所有主人操作仍由服务端复核权限和当前状态
