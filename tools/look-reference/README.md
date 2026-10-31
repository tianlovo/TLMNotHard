# 原版 LOOK 与跟随组合组件采集器

在固定 MC1.20.1、Forge47.2.0、TLM `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6` 和 Java17 环境中执行原版方法
本工具仍是组件采集，实体不登记、不自然 tick，不产生 AI 能力成绩

## 背景

`LOOK_SINK_CORE_V1` 直接执行原 `LookAtTargetSink(45,90)` 和 `Behavior.tryStart/tickOrStop/doStop`
`FOLLOW_MOVE_LOOK_COMPONENT_V2` 只注册 look0、move2、follow3 三行为，通过原 Brain 的 start/run 两 pass 执行
不运行 sensors、自然导航或原生 look helper tick，不能称作完整 Brain

原版 visibility 查询真实执行 `EntityTracker.isVisibleBy` 和 `NearestVisibleLivingEntities.contains`
候选 memory、owner 解析、导航和世界整数 RNG 是明确的受控依赖
观测不提前调用 isVisibleBy，避免提前填入可见性缓存

## 安装

环境变量 `TLMNH_LOOK_PROBE` 指向本目录，复用原 `TLMNH_REFERENCE_PROBE`、外部 `TLMNH_REFERENCE_HOME` 与既有构建锁/JDK
只应用本目录 `reference.init.gradle`，它应用已有原版 reference init
任务为 `prepareLookReferenceIdentity` 和 `lookDefinitionAgentJar`
新清单为外部 HOME 的 `identity/look-build.json`，不复用旧 follow/move 的原始 capture 或身份

启动显式启用 `tlmnh.lookProbe`，关闭 reference、geometry、follow、move、followNatural 五个旧入口
实际解析的编译资源摘要和 premain 定义摘要分列，只证明选定类，不证明整套 classpath

## 使用

```text
python tools/look-reference/control.py status
python tools/look-reference/control.py lab_open
python tools/look-reference/control.py capture_look
python tools/look-reference/compare.py <capture.json> --build-identity <look-build.json>
```

只允许原专用虚空实验世界中受保护的旁观客户端，未有存档时可用 `lab_create`
运行发生在实际集成服线程，保留9个场景、每个3份新实体/任务，共27份 row
LOOK 最短/最长 duration、等号与下一 tick、不可见但有 memory、移除/移动目标、眼部/脚部 tracker 和组合首次生效/到期均单独记录
世界 gameTime 与 RNG 引用在每份 row finally 独立恢复，不移动、删改真实玩家或库存
实体只 discard 自己未登记的实例，不改平台或普通存档块，也不修改 pauseOnLostFocus

实际故障注入发生在原方法执行之前，原正常 capture 不回填或覆写：

```text
python tools/look-reference/control.py capture_look --fault deadline-equality-plus-one
python tools/look-reference/control.py capture_look --fault target-feet
python tools/look-reference/control.py capture_look --fault look-priority-last
python tools/look-reference/compare.py <fault.json> --build-identity <look-build.json> --baseline <normal.json>
```

最后一条比较真实状态和调用，不仅检查 fault 标签或计划输入；检出差异时 exit1 是预期的负例结果
正常 A/A 成功只表示组件原始采样可重复，`aiCapabilityEvaluated=false`
旧 FOLLOW_CORE_V1 / MOVE_SINK_CORE_V1 保留原身份和范围，不能拼成新组合通过

### 独立 LOOK 移植重放

在源码根目录用 `--init-script tools/look-reference/replay.init.gradle prepareLookReplay` 准备 Java25 启动器
生成的 `build/look-reference/launcher.json` 只保存本机启动信息，保持忽略；类与源码摘要由外层实验另行绑定
入口为 `com.tianluoqaq.tlmnh.common.ai.faithful.LookCoreReplayMain`，参数为原版正常采集、新输出路径及可选故障

```text
LookCoreReplayMain <normal.json> <new-port-output.json> none
python tools/look-reference/compare_replay.py <normal> <port> --reference-tool <tool> --build-identity <identity>
```

上例第一项为主类参数示意，实际执行须使用启动器记录的 Java 与 classpath；`<tool>` 指本工具目录
重放仅读初态、明确外部补丁、记录的可见性依赖及世界整数流，再由实际移植 Driver 产生状态和请求
比较器先验证原版身份、输入与三次 A/A；输出不替外层证明实际 classpath，不能只靠 JSON 自证代码身份
只覆盖 21 行独立 LOOK，6 行组合 V2 明确排除；不比较原生 helper 的旋转物理
`deadline-equality-plus-one` 会实际提前一次方法时间，用状态与请求差异检出，原采集与旧输出不覆盖

## 维护者

[tianlovo](https://github.com/tianlovo)

## 参与贡献

运行前分别验证实际 API、身份、原始采样和移植重放，当前结果见 [Indev 进度](../../PROGRESS.md)
不得用 JSON 手算 expected、从后态回灌移植算法或省略 look 的 world.nextInt(46) 来保持旧轨迹

## 许可证

原创工具遵循项目当前许可声明，原版类通过已配置的 Minecraft/Forge 开发环境加载
只读身份 agent 复用既有实现并保留其来源，不分发整套 Minecraft 类或源码
