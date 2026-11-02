# 原版自然睡眠可行性采集

在固定TLM `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6`、Minecraft1.20.1、Forge47.2.0、Java17上
使用原生GameTest服务端观察完整Brain，不启动客户端，不执行移植JAR
这是B10的家园离线主人基础可行性采样，完整B10、移植等价和统计收益均未准入

## 输入与执行

`contract.json` 定义三只全新女仆各150帧、30tick原生POI预热、NIGHT日程与6000日时、家园和北向女仆床
主人只有离线UUID，未构造玩家；世界与实体RNG均不重置
精确原类通过真实世界登记和自然tick运行，实际Brain活动为core/rest，不手动tick Brain、调用寻路或设置睡姿
LevelTick END记录出生后的1～150实体年龄、每帧LivingTick数、位置、运动、睡姿、床占用和活动
采集结束后只清自己的新实体并恢复原日时及改动的规则，独立新GameTest世界不借用玩家存档

## 构建与运行

只在已有独立原版reference checkout使用本目录的 `reference.init.gradle`，不要对GTNH工程应用
它继承已审批的原工具链锁与原版源码检查，旧客户端观测patch保留，新的选定集合、身份清单及工作目录另建

- `TLMNH_REFERENCE_PROBE` 指向既有动画参考工具
- `TLMNH_SLEEP_NATURAL_PROBE` 指向本目录
- `TLMNH_REFERENCE_HOME` 为源码外的原版运行根，实际工作区为其 `sleep-gametest` 子目录
- 既有Git、Java17和工具链锁配置继续有效

任务为 `prepareSleepNaturalReferenceIdentity` 和 `runGameTestServer`
后者是[Forge原生入口](https://docs.minecraftforge.net/en/1.20.x/misc/gametest/)，仅启用本工具的测试namespace
模板是本工程生成的17×8×9空NBT，程序在其中铺石台和原女仆床

## 检查与边界

```text
python tools/sleep-natural-reference/check.py capture.json --build-identity sleep-natural-build.json --agent sleep-definition-agent.jar
```

捕获位于外部工作区的 `captures`，身份在外部根的 `identity/sleep-natural-build.json`
检查器核合同、实际编译资源、只读agent/清单绑定、首次定义记录、三只新实体及450帧完整性，再输出各次首次入睡时间
校验历史样本时，`--tool` 须指向该次冻结的工具源码；工具后续改动不能替换历史输入身份
定义字节摘要与资源摘要分别记录，差异原样报告，不凭摘要单独宣称已解释服务端变换或整个classpath完全相同
采集完成或GameTest成功表示观察路径完成，结果始终保持 `NOT_EVALUATED`，不能改为完整B10或移植验收
硬杀、崩溃原子性、多人、床被占用/拆除/受击与移植侧配对仍在本工具当前范围之外

## 许可证

本工具原创代码沿用项目许可证；固定上游及复用只读agent各自保留原有来源与许可
