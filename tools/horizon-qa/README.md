# Horizon-QA 服务端试点

本工具使用 Horizon-QA **0.15.0**，对应上游提交 `e05abc05be88e9072c7ed298e4ae81c158213515`
[官方接入文档](https://www.gtnewhorizons.com/Horizon-QA/getting-started/mod-setup/)

测试源码和结构位于本目录，使用独立 source set 生成 `tlmnh-horizonqa-pilot.jar`
默认构建、玩法依赖与正式 JAR 都不包含框架或这些测试

## 构建和运行

下载同版本开发 JAR，将 `TLMNH_HQA_DEV_JAR` 指向该文件，再执行：

```text
./gradlew -I tools/horizon-qa/pilot.init.gradle reobfHorizonQaPilot
```

构建会核对开发 JAR 的 SHA-256，并沿用项目的 Jabel、Java 8 输出与 RFG 重混淆
使用同一 init 时，`spotlessJavaApply` 仅格式化本目录中的试点 Java 文件

在新的专用 GTNH 测试副本中加载玩法 JAR、Horizon-QA 运行 JAR 和试点 JAR
将以下参数传给 Minecraft 服务端 JVM：

```text
-Dhorizonqa.mode=ci
-Dhorizonqa.tests=tlmnhqapilot:WaterTravelTests
-Dhorizonqa.gridOrigin=0,100,0
-Dhorizonqa.turbo=1
-Dhorizonqa.reportDir=<独立报告目录>
```

使用 RFG 的 `runServer` 时，每个参数分别通过 `--mcJvmArgs` 转交，不能把这些 `-D` 参数设置到 Gradle JVM

Indev95 的三个水域用例应退出 0：自然闲置上浮、封顶持续接触和受控岸边碰撞
涉及动物开关的用例分批运行；岸边演员不注册世界，直接驱动真实实体物理，不冒充自然 Root 行为

历史 Indev94 接入试点使用 `tlmnhqapilot:AquaticLifecycleTests.liveSwimmingBody`
仅在该候选上，加上 `-Dtlmnh.qa.injectPoseFault=true` 后，应因真实泳姿身体被改成 1.5 高而退出 1
该试点保留用于复查历史框架接入；95 修正无路径闲置语义后，不能沿用它“闲置自动进入主动泳姿”的前提
不要选择全部历史用例来判断当前候选，故障模式也不能作为正常通过运行

## 试点结论与边界

GTNH 2.9.0、Java25、Indev94 上的正常用例通过，故障版准确报告 `expected height=0.6 actual=1.5`
两轮 JUnit XML、状态 JSON 和实际进程退出码一致，均确认实体清理及全局动物开关恢复
初次因 CI 动物策略导致实体被删除的失败单独保留

Horizon-QA 的 CI 默认会调用 `setCanSpawnAnimals(false)`；旧 WorldServer 会因此逐 tick 删除 EntityAnimal
女仆继承该类型，所以用例先允许动物实体运行，并用 `afterTest` 恢复原值；这是一项测试前提，不替代女仆行为
改变这个全局状态的测试须独立批次运行，不能靠测试格距离获得隔离

本用例只完成一次自然入水观测和受控状态转换；睡眠、醒来请求在同一 END 阶段完成，不代表真实床行为或长期稳定性
Indev95 的常规上浮实际运行 169 tick、封顶 60 tick、受控岸边 39 步；封顶起点后来提高并单独重跑，确认持续触顶，无穿透或空气恢复
后续新增服务端用例可复用其调度、场景与报告，逐步接入，不批量重写已有测试
客户端画面、完整水域行为和原版 1.20.1 的 O/F/N 对照仍使用各自验证流程

## 新增两栖导航场景

Indev96 使用 `-Dhorizonqa.tests=tlmnhqapilot:AmphibiousNavigationTests`
四组分别验证自然 Root 水下跟随、低顶水道、封闭部分路线/执行中坐下取消，以及旧地面路线执行
自然世界 tick 和碰撞由服务端执行，合成主人是受控目标；用例不直接推动女仆，不将它冒充真实多人或原版运行对照

夹具的持续加载范围包含原生路径查询的外围区块，使用独立 `GameTestChunkLoader` 票据并在结束释放
此处使用固定 0.15.0 的公开 internal 类，不承诺兼容其他框架版本
合成主人沿用本项目无外部连接的内存通道，结束时移除并释放消息；仅往玩家列表添加无连接对象可能使整包区块补发器崩溃

正常四组进程退出 0，XML/JSON 无失败、跳过或基础设施错误；取消断言加强后先重跑该组，确认在尚未走完的新路线上取消
最终玩法候选读取实际跌落能力后，再合并重跑四组通过；不同 JAR 的原始记录分别保存
首轮不完整报告与崩溃保留，不能凭进程退出 0 推定框架已完成报告或测试通过

## 地面、梯子与水陆交接

Indev97 选择 `-Dhorizonqa.tests=tlmnhqapilot:TraversalNavigationTests`
四个独立批次覆盖木门/栅栏门权限及通过后关闭、远处梯子往返、宽池水陆交接/活路线取消，以及复用的一格高水道
门的权限关闭后仍须持续挡住女仆；折返前须真实站上对岸；取消必须发生在携带原地面取消凭据、尚未执行完的水路线中
全程核对真实碰撞、健康和逐 tick 位移，不能由低氧执行器代替普通导航

岸面高于水位，池长保证入水后仍能遇到至少五个水节点；改变地形必须重新核对这些前提
初版水位漫岸的失败保留；一格宽水槽还暴露 WATER_BORDER 竖直恢复限制，宽池交接不能覆盖该问题
岸边须在跟随停止距离之外，不能在停止阈值内要求继续走；这里的玩法验证不替代自然原版对照
客户端共同构造与实体跟踪另用真实联网客户端验证，不把隔离内存主人当作多人测试

Indev97 最终候选四组退出 0，状态 JSON/JUnit XML 一致，无失败、跳过或基础设施错误
岸边站姿漏检浅水的问题修正后，才计入水陆交接通过；旧失败、窄槽限制和撤回的推断分别保留

## 现代 Follow/Move/Look 组件

Indev98 选择 `-Dhorizonqa.tests=tlmnhqapilot:ModernCoreTests`，并显式开启 `-Dtlmnh.devCommands=true`
四个独立批次分别检查动态主人与两次实际查询、深水三维执行及活路线取消、真实 watchdog 与部分路径 CANT_REACH，以及旧创建拒绝/策略撤销保留外来路线
组件通过开发用 after-navigation 入口安装，不改变默认玩法调度，也不启用完整 F/N

主人采用标准 Forge FakePlayer、隔离内存通道，并同时登记世界与服务器全局在线列表，结束时逐项移除
普通 EntityPlayerMP 直接加入全局表会因 ServerUtilities 缺失登录档案崩溃，该原始无报告运行单独保留，不能用进程退出 0 推定通过
夹具必须尊重原版 Behavior 的 150～250 tick 上限：重规划不续期；动态用例检查到达或原期限后正常清理，不能假定有限时行为一定走完整程
watchdog 场景在首个检测窗内被物理墙挡住，避免行为时限先到而掩盖卡住检测；水路线 PATH 按管理器末尾换路、Sink 下一帧导航后更新的相位断言

这些仍是受控主人和局部组件验证，不替代真实多人、完整 F 或原版自然运行 benchmark

Indev98 最终候选的四个组件场景通过，分别运行 216/136/109/8 tick；状态 JSON、JUnit XML 和进程退出 0 一致，无失败或跳过
本批未启动客户端；真实多人、全任务与原版运行对照继续保留独立验收

同一最终候选另复验默认原型 Root 的水陆交接与 `1/8` 活续路线取消，171 tick 通过，退出与两种报告一致
