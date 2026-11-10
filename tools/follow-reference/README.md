# 原版跟随组件采集器

本工具在独立环境中运行固定上游 TLM 1.20.1 的 `MaidFollowOwnerTask`，为 `FOLLOW_CORE_V1` 合同保存原版真实执行结果。
工具代码不进入 GTNH 模组产物，不运行完整 `Brain` 或 `MoveToTargetSink`，也不直接产出自然跟随表现或行为智能测评成绩。

## 背景

运行环境固定为上游提交 `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6`、Minecraft 1.20.1、Forge 47.2.0 与 Java 17。
每个输入用例都会新建三套独立的女仆、主人与任务状态，在真实的集成服务端线程上直接调用原版 `Behavior.tryStart` 与 `tickOrStop`。
全套 34 个输入用例覆盖跟随起停阈值、动态半径、主人状态、移动许可、应急呼吸分支，以及原生传送的干燥平台、足部阻挡和头部阻挡子用例。

测试主人为未注册到世界的真实 `ServerPlayer` 子类，`getOwner` 引用、旁观状态与水下标记均为显式受控依赖，本工具不验证原版在线玩家的 UUID 查询链路。
女仆实例保留原版 `Brain`、游泳管理器与原生导航管理器，导航包装器仅在记录调用参数后委托给 `super.resetNavigation`。
`return_true` / `return_false` 传送桩仅返回指定布尔值，不手工模拟记忆清理；原生传送子用例则直接调用真实的 `super.teleportToOwner`。
原版记忆槽位变化与对象引用保留关系全部来自真实运行观测，既不根据 `reset` 调用次数反推记忆清理，也不手写期望算法。

## 安装

请先准备好既有 `animation-reference-1.20.1` 所需的独立上游源码 checkout、最小运行依赖与已审批工具链锁。
构建上游参考工程时仅需传入本目录的 `reference.init.gradle`；该脚本内部会自动加载原版基础参考 init，请勿重复传入基础 init 脚本。
在本机环境变量中配置以下路径，切勿将本机路径硬编码进仓库：

- `TLMNH_REFERENCE_PROBE`：既有动画参考工具目录。
- `TLMNH_FOLLOW_PROBE`：本工具所在目录。
- `TLMNH_REFERENCE_HOME`：位于源码仓库之外的独立参考运行目录。
- 既有的 `TLMNH_REFERENCE_GIT`、工具链锁与构建身份环境变量继续沿用原有约束。

`prepareFollowReferenceIdentity` 任务依赖原版严格身份核验任务与 `followDefinitionAgentJar`。
`runClient` 会自动挂载只读类定义观察 agent，该 agent 复用 `geometry-reference/agent` 源码，不修改任何类字节码。
构建身份清单输出至外部运行目录的 `identity/follow-build.json`，记录原版基础锁、源码提交、工具脚本、资源文件及实际编译类摘要。
运行记录同时保留选定类的资源字节摘要与首次类定义字节摘要，不代表整个 classpath 的完整身份证明，也不排除其他平台类变换。

## 使用

以下命令由维护者在配置好的独立参考环境中执行，常规 GTNH 模组构建不会触发本工具：

```text
python tools/follow-reference/control.py status
python tools/follow-reference/control.py lab_create
python tools/follow-reference/control.py capture_follow
python tools/follow-reference/compare.py <capture.json> --build-identity <follow-build.json>
python tools/follow-reference/compare.py <capture.json> --build-identity <follow-build.json> --replay <port-replay.json>
```

若专用虚空实验世界已经存在，请使用 `lab_open` 打开，严禁覆盖既有实验存档。
控制与结果文件均位于运行目录的 `client/tlmnh-follow-reference` 下，请求、响应与原始采样完整留存。
`capture_follow` 要求处于专用虚空场景且观察玩家具备受保护旁观状态，组件测试实体不会注册到世界或参与自然 tick。
原生传送子用例仅在预检确认全空的隔离平台区域临时放置石块，并在退出时校验恢复；临时替换的 RNG 引用也必须在 `finally` 中还原。
`Behavior` 持续时间采样所消耗的世界 RNG 与女仆传送消耗的实体 RNG 分开记录，三次独立重建均使用同一受控种子。

采集阶段只负责如实保存观测数据，不会自动判定通过。
`compare.py` 会严格校验完整的 `34×3` 条记录与三次新建实例的原版 A/A 重复性，随后仅提取 `normalised` 字段与实际方法调用轨迹用于移植端组件比对。
原版 `Behavior` 内部状态、世界 RNG、女仆实体 RNG、原始坐标与碰撞箱仍完整保留在原始采样文件中，不将其冒充为 1.7.10 完整运行时的等价还原。
输入故障负例必须作用于真实执行入口（例如重放驱动在调用原方法前微调精确边界距离），不得通过手工篡改输出 JSON 伪造差异。
比较器会记录首个分歧所在的 `case`、`rebuild` 与具体字段路径；一旦原版 A/A 自身不稳定，立即中止后续准入。

当前 `34×3` 受控原版组件样本已具备真实采集、原版 A/A、移植决策重放及真实输入故障检出记录。
每一轮采样仍须独立通过自身的构建身份、输入绑定与数据完整性门禁；组件重放通过不自动外推为 RC2 原生传送或自然世界跟随验收通过。
`C05` 移动 sink、原版完整 `Brain` 自然跟随、跨维度搬运与自然水下呼吸均不在本工具覆盖范围内。

## 维护者

[tianlovo](https://github.com/tianlovo)

## 参与贡献

修改合同定义、原版受测方法、采集探针或构建工具链后，须重新生成构建身份并采集三次新建实例的原版 A/A 样本。
严禁手工修补既有原始采样文件，也不得将组件级对照结论夸大为自然行为验收证明。

## 许可证

本工具原创代码沿用项目许可证，固定上游源码及既有参考工具各自保留原有的来源与许可声明。
