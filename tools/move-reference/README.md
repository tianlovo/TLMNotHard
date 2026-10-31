# 原版移动 sink 组件采集器

在固定 TLM 1.20.1 参考环境中直接执行原 `MoveToTargetSink` 与 `Behavior` 方法
该工具不进入 GTNH JAR，不运行自然 Brain，不证明原生导航或整个 fallback 算法已经移植

## 背景

环境固定为上游 `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6`、Minecraft 1.20.1、Forge 47.2.0、Java 17
真实 Brain 和 Path 对象配合受控导航、时钟、世界整数 RNG、稳定性、malus 和评分依赖，调用原 `tryStart`、`tickOrStop`、`doStop`
23 个输入场景、47 个方法步骤，每例三份新实体和新任务，记录真实状态而不手算期望
场景覆盖 duration 的 150/250 与等号、仅合法开始检查递减的冷却、三维 Manhattan2/3、严格>4 重规划、完整/部分/空路径、fallback、普通停路与卡住的区别、路径替换和回调中已到达

## 安装

复用已有独立上游 checkout、原动画参考 init 和已审批工具链锁，旧 follow baseline 工具保持原样
新环境变量 `TLMNH_MOVE_PROBE` 指向本目录，保留 `TLMNH_REFERENCE_PROBE`、外部 `TLMNH_REFERENCE_HOME`、原 Git/锁/JDK 配置
只向参考构建传入本目录 `reference.init.gradle`，它会应用原 base init，不要重复应用 base
任务为 `prepareMoveReferenceIdentity`、`moveDefinitionAgentJar`，runClient 自动依赖独立清单
清单位于外部 HOME 的 `identity/move-build.json`，公开工具用环境变量提供本机位置
清单和原始报告保留在外部运行目录，公开分发前单独核对内容

原版 vanilla 类资源从实际解析的 compileClasspath JAR 读取并记录其 JAR/资源摘要，不从缓存候选推断历史身份
运行时用复用的只读 premain agent 记录选定类首次定义，资源摘要与定义摘要分列；这不是全部 classpath 身份保证

## 使用

```text
python tools/move-reference/control.py status
python tools/move-reference/control.py lab_create
python tools/move-reference/control.py capture_move
python tools/move-reference/compare.py <capture.json> --build-identity <move-build.json>
python tools/move-reference/compare.py <capture.json> --build-identity <move-build.json> --replay <port-replay.json>
```

已有专用虚空世界使用 `lab_open`，不得覆盖
请求/响应/原始记录保留在外部 HOME 的 `client/tlmnh-move-reference`，只允许受保护的集成服旁观环境
采集运行在真实 server.execute 线程，组件实体不登记到世界且不自然 tick；方法时钟与实际临时设置的 world gameTime 分开记录
finally 恢复原 gameTime 与 RNG 引用，清理失败不得准入

每份 row 只保存一次实际 `initialState`，每 step 的 `input.externalPatch` 只代表显式外部输入
原版内部 sink 和 Brain 状态在各步骤之间真实连续，不在下一步从参考答案重建
移植重放同样必须持续维护自己的状态，不能用 `before` 或 `after` 回灌算法

`callTrace` 只比较实际导航 create/move/stop 和世界整数 RNG 请求，`moveObservations`、`callbackObservations` 保留受控依赖的实际副作用
fallback 由原 `DefaultRandomPos` 真正执行，女仆随机流、稳定性、malus 与得分单独记录
原 coordinate createPath 入口为 final，工具直接观察的是其内部取整后的 BlockPos 查询；`directVec3Observed=false`，不能把归一化重放称作完整浮点采样实现证明
传给纯移植核心的 fallback 适配只能消费实际观察到的结果，不能用移植算法生成自己的 expected

`compare.py` 先严格核对三份新状态的原版 A/A，再可比较实际移植输出的状态、方法返回和调用
负例必须作用于真正执行的方法或输入，例如截止等号的调用实际多推进一 tick，比较器应从状态/效果检出，不能只依赖故障标签
源码交付时尚未构建、运行采集或通过 A/A；这些由独立参考运行验证

## 维护者

[tianlovo](https://github.com/tianlovo)

## 参与贡献

保留失败原始记录，修改工具/合同/依赖后重新取得严格身份及三份新状态 A/A
不得更改旧 baseline、修补输出 JSON 或将组件结果升级成自然行为结论

## 许可证

原创工具代码沿用项目许可证，原版类和复用参考 agent 保留各自来源声明
