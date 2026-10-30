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

实体同步索引 20–27 分别为模型、任务、作息、经验、饥饿、好感、拾取标记和副手，28 为短时动作标记；主手与护甲沿用原版装备同步，副手独立保存并在数量、耐久或 NBT 原地变化后同步副本

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

主人普通右键打开六格背包、双手和护甲界面，可以设置任务、作息、拾取与坐姿；潜行空手右键切换坐姿，坐下时清除当前目标与路径；模型选择界面、动画、行为和其余物品交互仍待实现，选择战斗任务不代表已经能够作战

CommonProxy 创建服务端容器，其初始化入口不引用客户端类型；ClientProxy 注册 GUI、女仆渲染器和资源重载监听；开发开关开启时，`tlmnhdev spawn [玩家]` 在在线玩家附近生成一只绑定测试女仆

`tlmnhdev interaction-test` 使用未加入在线列表的服务器玩家检查蛋糕消耗、首次绑定、拒绝换主人、坐姿和超距拒绝，成功标记为 TLMNH_INTERACTION_TEST_PASS；它不发送真实玩家网络点击，也不打开客户端界面

## 维护边界

普通功能提交不打 tag，只在完成小阶段或里程碑时创建标签；日常构建用源码提交号、显式开发版本与 JAR SHA-256 追踪

内置模型资源使用 `scripts/import_builtin_assets.py --upstream <固定 TLM 克隆路径>` 静态核对，显式添加 `--write` 才导入；脚本要求固定提交且相关上游文件没有修改，保留原字节与原始作者说明，生成模型装饰 ID 和逐文件来源摘要，不导入椅子或 LLM 设置

使用 `python scripts/verify_builtin_assets.py [候选JAR]` 核对源码或打包后资源摘要及引用；`tlmnhdev model-test` 在服务端检查 254 个装饰 ID 的 NBT 往返、酒狐 19 个 Gecko 基础条目与 2 个 Bedrock 基础条目合计 35 个选择，以及未知模型回退；上述检查均不能替代客户端渲染和动画验收

`build` 包含 Java 25 上运行的 JUnit 几何测试，测试源码仍通过 Jabel 编译为 Java 8 字节码；可用 `./gradlew.bat test --tests '*BedrockGeometryTest'` 单独检查 Bedrock 几何，`LegacyPoseStackTest` 检查矩阵与法线变换

几何测试直接向内存顶点接收器输出，覆盖全部 205 份非 Gecko 内置几何，包含两款 Bedrock 酒狐；不会创建 GL 上下文，不能据此宣称游戏内渲染或 Gecko 动画完成；JOML 仅打包 Java 数学类并重定位，字节码检查仍扫描它们

`GeckoGeometryTest` 覆盖 26 份内置 Gecko 几何与独立骨骼姿态，检查酒狐 19 个基础条目和 33 个 Gecko 选择的几何输入、镜像、逐面 UV、隐藏控制、定位骨骼、快照及异常矩阵清理；这里的 AnimatedGeoModel 是可变姿态实例，关键帧、Molang 和女仆状态控制器尚未接入

模板默认测试类路径使用 Gson 2.2.4，`testRc2ClientGson` 以 RC2 客户端的 Gson 2.10.1 再运行两条几何路径；两组测试都使用 Java 25，均由 `check` / `build` 执行，兼容性测试依赖不会打包进模组，也不会替换整包的 Gson

`MaidRenderer` 继承 RC2 的 RenderLiving，将同步的模型 ID、原贴图和实体缩放接入生物渲染；解析后的几何按资源缓存，可变姿态按女仆持有弱引用缓存，资源重载后两者失效；模型资源损坏时记录错误并尝试默认模型，这种回退不能算作该模型验收通过

`BuiltinMaidModelsTest` 逐项创建并输出 254 个选择，包含酒狐 35 个选择，检查两种坐标的转换、姿态隔离、资源重载、流关闭与异常清理；Gecko 目前仍输出初始姿态，控制器、装备层、选择界面和实际 GL 验收待完成

Bedrock 渲染入口按资源中的顺序执行上游内置动画组合，每帧先恢复全部骨骼与整体变换；两个固定 JS 已逐式转为 Java，仍按原资产许可提供，不执行外部脚本；`BedrockPoseTest` 检查复位、嵌套绑定和异常清理，`BuiltinBedrockAnimationsTest` 让全部 213 个 Bedrock 选择各输出十种姿态输入，另查左右手、护甲、生命值与两个脚本的公式

`MaidPose` 的睡眠、乞食、用手、副手挥动和拉弓标记由服务端同步，动画侧读取一次绘制的快照，动作标记不写入存档；当前接入了原有坐姿、骑乘、摆动、生命和装备状态，触发睡眠、乞食、用物品与拉弓的生活或战斗行为仍待实现，硬编码游泳等动作层也未完成；CPU 姿态检查通过不能作为这些玩法或画面的验收

上游来源与许可见 [第三方说明](THIRD_PARTY_NOTICES.md) 和 [移植记录](SOURCE_PROVENANCE.md)，复制原文件时保留包名，独立适配文件使用 com.tianluoqaq.tlmnh

修改存档、槽位、实体 ID 或网络字段需要记录迁移方式；公共侧不得引用客户端渲染类，所有主人操作仍由服务端复核权限和当前状态
