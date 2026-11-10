# 原版 LOOK 与跟随组合组件采集器

本工具在固定的 Minecraft 1.20.1、Forge 47.2.0、上游 TLM 提交 `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6` 及 Java 17 环境中直接执行原版方法。
工具定位仍为组件级采样：受测实体不注册进世界、不参与自然 tick，也不产出行为 AI 能力评分。

## 背景

`LOOK_SINK_CORE_V1` 直接调用原版 `LookAtTargetSink(45, 90)` 及 `Behavior.tryStart/tickOrStop/doStop`。
`FOLLOW_MOVE_LOOK_COMPONENT_V2` 仅注册 `look0`、`move2`、`follow3` 三项行为，经由原版 `Brain` 的 `start/run` 两阶段推进。
采样过程不运行传感器、自然寻路导航或原生 `look helper` 的逐帧转向，不能视作完整 `Brain` 运行。

原版可见性查询真实调用 `EntityTracker.isVisibleBy` 与 `NearestVisibleLivingEntities.contains`。
候选记忆、主人解析、导航接口及世界整数 RNG 均作为显式受控依赖注入。
观测代码不会提前调用 `isVisibleBy`，避免预先污染可见性缓存。

## 安装

将环境变量 `TLMNH_LOOK_PROBE` 指向本目录，复用既有的 `TLMNH_REFERENCE_PROBE`、外部 `TLMNH_REFERENCE_HOME` 及既有工具链锁与 JDK 配置。
构建时仅传入本目录的 `reference.init.gradle`；该脚本内部会自动引入已有的原版基础参考 init。
身份构建任务为 `prepareLookReferenceIdentity` 与 `lookDefinitionAgentJar`。
新身份清单写入外部 `HOME` 的 `identity/look-build.json`，不混用历史 `follow` 或 `move` 工具的原始采样与构建身份。

启动时显式开启 `tlmnh.lookProbe`，并关闭 `reference`、`geometry`、`follow`、`move`、`followNatural` 五个既有探针入口。
实际解析的编译资源摘要与 `premain` 首次定义摘要分列保存，仅覆盖选定类集合，不构成整套 classpath 的身份证明。

## 使用

```text
python tools/look-reference/control.py status
python tools/look-reference/control.py lab_open
python tools/look-reference/control.py capture_look
python tools/look-reference/compare.py <capture.json> --build-identity <look-build.json>
```

采样仅允许在原专用虚空实验世界中由受保护的旁观客户端发起；首次尚无实验存档时可执行 `lab_create`。
采样逻辑运行在真实的集成服务端线程上，共包含 9 个场景，每个场景新建 3 份独立实体与任务实例，合计 27 行记录。
`LOOK` 最短/最长持续时间、截止等号与下一 tick 边界、不可见但保留记忆、目标移除或位移、眼部/脚部 `tracker` 以及三组件组合的首次生效与到期均独立记录。
世界 `gameTime` 与 RNG 引用在每行记录的 `finally` 块中独立恢复，不会移动或修改真实玩家及其库存。
受测实体仅在结束时 `discard` 自身未注册的临时实例，不改动实验平台或存档方块，也不修改 `pauseOnLostFocus`。

故障负例在原版方法执行前真实注入，不会回填或覆盖已生成的正常采样文件：

```text
python tools/look-reference/control.py capture_look --fault deadline-equality-plus-one
python tools/look-reference/control.py capture_look --fault target-feet
python tools/look-reference/control.py capture_look --fault look-priority-last
python tools/look-reference/compare.py <fault.json> --build-identity <look-build.json> --baseline <normal.json>
```

最后一条命令会比对真实运行状态与调用序列，而非仅检查外层故障标签或输入计划；检出分歧时返回退出码 `1` 即为预期的负例表现。
正常样本通过 A/A 仅说明组件原始采样具备可重复性，报告中 `aiCapabilityEvaluated` 固定为 `false`。
既有 `FOLLOW_CORE_V1` 与 `MOVE_SINK_CORE_V1` 保留各自的构建身份与适用边界，不能直接拼接替代新组合合同。

### 独立 LOOK 移植重放

在源码根目录执行 `--init-script tools/look-reference/replay.init.gradle prepareLookReplay` 可准备 Java 25 重放启动器。
生成的 `build/look-reference/launcher.json` 仅保存本机启动参数并保持 Git 忽略，实际类与源码摘要由外层实验另行绑定。
重放主类为 `com.tianluoqaq.tlmnh.common.ai.faithful.LookCoreReplayMain`，依次接收原版正常采样路径、新输出路径及可选故障名：

```text
LookCoreReplayMain <normal.json> <new-port-output.json> none
python tools/look-reference/compare_replay.py <normal> <port> --reference-tool <tool> --build-identity <identity>
```

上例第一行为主类参数示意，实际运行时须使用启动器清单记录的 Java 可执行文件与精确 classpath；`<tool>` 指向本工具目录。
重放器仅读取初始状态、显式外部补丁、已记录的可见性结果及世界整数随机流，随后由真实的移植版 `Driver` 计算状态与调用请求。
比较器会先核验原版身份、输入完整性与三次 A/A 一致性；输出文件本身不替外层实验自证 classpath，不能仅凭 JSON 字段宣称代码身份已核实。
当前独立重放仅覆盖 21 行 `LOOK` 单组件场景，6 行三组件组合 V2 明确排除在外，且不比较原生 `look helper` 的头部旋转物理。
注入 `deadline-equality-plus-one` 会让方法时间真实提前一步，由状态与调用差异触发检出，不会覆盖原始采样与历史正常输出。

## 维护者

[tianlovo](https://github.com/tianlovo)

## 参与贡献

运行前后请分别校验真实 API、构建身份、原始采样与移植重放，最新实测进展见 [Indev 进度](../../PROGRESS.md)。
严禁手算 JSON 期望值、根据后态反向回灌移植算法，或为迁就旧轨迹而省略 `look` 行为消耗的 `world.nextInt(46)`。

## 许可证

本工具原创代码遵循项目现行许可证声明，原版类通过已配置的 Minecraft/Forge 开发环境按需加载。
只读身份观察 agent 复用既有实现并保留原来源声明，不随本仓库分发 Minecraft 类或源码。
