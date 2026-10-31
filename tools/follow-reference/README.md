# 原版跟随组件采集器

独立运行固定 TLM 1.20.1 的 `MaidFollowOwnerTask`，为 `FOLLOW_CORE_V1` 保存原版执行结果
该工具不进入 GTNH 模组，不执行完整 Brain 或 `MoveToTargetSink`，也不提供自然行为或智能测评成绩

## 背景

上游固定为 `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6`，Minecraft 1.20.1、Forge 47.2.0、Java 17
每个输入创建三份新女仆、主人和任务状态，在真实集成服线程调用原版 `Behavior.tryStart` 与 `tickOrStop`
34 个输入覆盖跟随阈值、动态半径、主人状态、移动许可、应急呼吸分支，以及原生传送的干燥平台、足部阻挡和头部阻挡子例

主人是未登记到世界的真实 `ServerPlayer` 子类，`getOwner` 引用、旁观和水下标记是受控依赖，未证明原版在线 UUID 查找
女仆保留原版 Brain、游泳管理器和原生导航管理器，导航包装器只记录调用后委托 `super.resetNavigation`
`return_true`/`return_false` 传送依赖只返回值，不模拟记忆清除；原生传送子例直接调用 `super.teleportToOwner`
原版记忆和对象保留关系均实际观察，不从 reset 次数推造清除，也不手写期望算法

## 安装

先准备既有 `animation-reference-1.20.1` 的独立上游 checkout、最小运行依赖和已审批工具链锁
只对该上游构建传入本目录的 `reference.init.gradle`，它内部应用原版参考 init，不要再次重复应用原 init
在本机环境设置这些位置，不把路径写入仓库：

- `TLMNH_REFERENCE_PROBE`：既有动画参考工具目录
- `TLMNH_FOLLOW_PROBE`：本工具目录
- `TLMNH_REFERENCE_HOME`：源码以外的独立参考运行目录
- 既有 `TLMNH_REFERENCE_GIT`、工具链锁与构建身份环境变量保持原有约束

`prepareFollowReferenceIdentity` 依赖原版严格身份任务与 `followDefinitionAgentJar`
`runClient` 自动接入额外的只读定义观察 agent；它复用 `geometry-reference/agent` 源码，不修改类字节
构建清单写入外部运行目录的 `identity/follow-build.json`，记录原版基础锁、源码、工具、资源及实际编译类摘要
运行记录包含选定类的资源摘要和实际首次定义字节摘要，不代表完整 classpath 身份或没有其他平台变换

## 使用

以下命令由维护者在配置好的独立参考环境运行，普通 GTNH 构建不执行本工具

```text
python tools/follow-reference/control.py status
python tools/follow-reference/control.py lab_create
python tools/follow-reference/control.py capture_follow
python tools/follow-reference/compare.py <capture.json> --build-identity <follow-build.json>
python tools/follow-reference/compare.py <capture.json> --build-identity <follow-build.json> --replay <port-replay.json>
```

已有专用虚空世界使用 `lab_open`，不得覆盖原世界
控制文件位于运行目录的 `client/tlmnh-follow-reference`，请求、响应和采样均保留
`capture_follow` 要求专用虚空场景和受保护的旁观观察者，组件实体不会登记到世界或自然 tick
原生传送子例只在预检为空的隔离平台临时放置石块，并在退出时核对及恢复；原 RNG 引用也必须在 finally 中恢复
Behavior 持续时间消耗的 world RNG 与女仆传送 RNG 分开记录，各次新建使用同一受控种子

采样先保存事实，不自动标通过
`compare.py` 严格检查完整的 34×3 条目、三次新建的原版 A/A，并仅将 `normalised` 与实际调用轨迹用于移植组件对照
原版 Behavior 状态、world RNG、女仆 RNG、坐标及碰撞箱仍留在原始采样中，不冒充 1.7.10 完整运行时已还原
输入故障必须作用于实际执行，例如重放入口在调用原方法前改变精确边界距离；不能编辑输出 JSON 制造差异
首次分歧保留 case、rebuild 和字段位置，原版 A/A 不稳定时先停止准入

34×3 受控原版组件样本已有实际采集、自身 A/A、移植决策重放及真实输入故障检出记录
每份采集仍须通过自己的身份、输入绑定与完整性门槛；这些记录不扩大为 RC2 原生传送或自然跟随验收
`C05` 移动 sink、原版完整 Brain 自然跟随、跨维度搬运和自然呼吸仍在本工具范围之外

## 维护者

[tianlovo](https://github.com/tianlovo)

## 参与贡献

修改合同、原方法、采集器或工具链后重新构建身份并取得三次新建的原版 A/A
不要手工修补已有原始采样或将组件记录升级成自然行为证明

## 许可证

本工具原创代码沿用项目许可证，固定上游及既有参考工具的来源与许可保留各自声明
