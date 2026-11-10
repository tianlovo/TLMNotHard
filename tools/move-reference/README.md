# 原版移动 sink 组件采集器

本工具在固定的上游 TLM 1.20.1 参考环境中直接调用原生 `MoveToTargetSink` 与 `Behavior` 方法。
工具代码不打包进 GTNH 模组 JAR，不运行自然 Brain 循环，也不用于证明原生寻路或整套 fallback 搜索算法已完成移植。

## 背景

运行环境固定为上游提交 `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6`、Minecraft 1.20.1、Forge 47.2.0 与 Java 17。
工具使用真实的 `Brain` 与 `Path` 对象，配合受控导航、时钟、世界整数 RNG、落点稳定性、malus 代价及评分依赖，依次调用原版 `tryStart`、`tickOrStop` 和 `doStop`。
全量合同包含 23 个输入场景、47 个方法推进步，每个场景新建三组独立实体与任务实例，直接记录真实运行状态而不手算期望值。
覆盖范围包括持续时间 `150/250` 及其等号边界、仅在合法启动检查时递减的冷却计数、三维曼哈顿距离 `2/3`、严格 `>4` 重规划条件、完整/部分/空路径、fallback 分支、普通停路与卡住判定的区分、路径替换以及启动回调内即已到达等边界。

## 安装

复用既有的独立上游源码 checkout、动画参考初始化脚本与已审批工具链锁，原有 `follow` 基线工具保持不变。
配置新增环境变量 `TLMNH_MOVE_PROBE` 指向本目录，同时保留 `TLMNH_REFERENCE_PROBE`、外部 `TLMNH_REFERENCE_HOME` 及原有 Git/工具链锁/JDK 配置。
参考构建仅需传入本目录的 `reference.init.gradle`；该脚本内部会自动引入基础参考初始化脚本，切勿重复叠加基础脚本。
构建任务为 `prepareMoveReferenceIdentity` 与 `moveDefinitionAgentJar`，`runClient` 会自动依赖独立的身份清单。
身份清单写入外部 `HOME` 下的 `identity/move-build.json`；公开脚本统一通过环境变量读取本机目录，清单与原始采样报告均保留在仓库外的运行目录中，公开分发前需单独核查内容。

原版 Minecraft 类资源从实际解析的 `compileClasspath` JAR 中读取并记录 JAR 与资源摘要，不根据本地缓存目录猜测历史构建身份。
运行时通过复用的只读 `premain` agent 记录选定类的首次定义字节，资源摘要与首次定义摘要分列保存，不代表整个 classpath 的等价性保证。

## 使用

```text
python tools/move-reference/control.py status
python tools/move-reference/control.py lab_create
python tools/move-reference/control.py capture_move
python tools/move-reference/compare.py <capture.json> --build-identity <move-build.json>
python tools/move-reference/compare.py <capture.json> --build-identity <move-build.json> --replay <port-replay.json>
```

若专用虚空实验世界已存在，请改用 `lab_open` 打开，工具拒绝覆盖已有存档。
请求、响应与原始采样均保存在外部 `HOME` 的 `client/tlmnh-move-reference` 目录下，且仅允许在受保护的集成服务端旁观环境中采集。
采样逻辑在真实的 `server.execute` 线程中运行，组件实体不注册进世界，也不触发自然 tick；方法逻辑时钟与临时设置的世界 `gameTime` 分开记录，`finally` 块负责恢复原 `gameTime` 与 RNG 引用，任何清理失败均不予准入。

每组样本仅记录一次真实的 `initialState`，后续每一步的 `input.externalPatch` 只表示显式施加的外部输入。
原版 `MoveToTargetSink` 与 `Brain` 的内部状态在各步之间自然延续，不会在下一步开始前用参考答案重置；移植端重放同样必须连续维护自身状态，不得读取 `before` 或 `after` 快照反向回灌算法。

`callTrace` 仅比对实际的导航 `create/move/stop` 调用与世界整数 RNG 消费，`moveObservations` 和 `callbackObservations` 则完整保留受控依赖产生的真实副作用。
fallback 分支由原版 `DefaultRandomPos` 实际执行，女仆随机流、稳定性、malus 代价与候选评分均独立记录。
由于原版坐标级 `createPath` 入口为 `final` 方法，本工具直接观测到的是其内部取整后的 `BlockPos` 查询，因此标记 `directVec3Observed=false`，不能把归一化重放称为完整浮点采样实现的等价证明。
提供给纯移植核心的 fallback 适配层只能消费实际观测记录，严禁调用移植侧算法自行伪造期望值。

`compare.py` 先严格校验三组新建实例的原版 A/A 重复性，通过后才比对移植端输出的状态、方法返回值与调用序列。
故障负例必须作用于真实执行路径或输入参数（例如让截止时间等号判断多推进 1 tick），比较器须从运行状态或副作用差异检出故障，不能依赖外层故障标签判分。
工具源码首次入库时尚未附带构建产物、原始采样或 A/A 通过结论，相关证据均由独立的参考环境运行产出。

## 维护者

[tianlovo](https://github.com/tianlovo)

## 参与贡献

请完整保留失败运行的原始记录；修改工具代码、合同定义或构建依赖后，须重新生成严格身份清单并重跑三组新建实例的原版 A/A。
严禁篡改历史基线、手工修补输出 JSON，或将组件级重放结果夸大为自然行为验收结论。

## 许可证

本工具原创代码沿用项目许可证，所调用的原版类及复用的只读参考 agent 各自保留原有来源与许可声明。
