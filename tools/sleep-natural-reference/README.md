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
硬杀、崩溃原子性、多人及完整统计验收仍在本工具当前范围之外；五机制探索见下文

## 移植侧观察

`tools/horizon-qa` 的 `NaturalSleepPortTests` 使用同一合同，在独立HQA世界分别选择完整F和N
它直接绑定离线UUID，不构造玩家；家园三个活动点、六格睡眠范围、日时、北向床和初始位置在移植侧实际检查
每组观察三只全新女仆，各150帧；采集成功只表示记录完整，不能把自然睡眠缺失或轨迹异常判为功能通过

`compare_port.py` 接收原样本、冻结原工具/构建身份/agent、两个移植捕获、实际候选和pilot JAR，以及HQA JSON、observed和JUnit
原版参数为 `--original-capture`、`--original-tool`、`--original-build`、`--original-agent`
移植参数为重复的 `--port-capture`、`--candidate-jar`、`--pilot-jar`
运行与输出参数为 `--hqa-result`、`--observed`、`--junit` 和 `--output`
检查实际资源身份、工厂选择、自然tick序列和清理后，只报告首次入睡、睡眠帧数、床头水平偏移、高度及运动范围
原版尚未记录全部初始场地、天气等实际输入，世界RNG及跨版本物理也未控制；因此保持 `NOT_EVALUATED`，没有完整配对输入、B10或统计准入
可加 `--require-stable-sleep` 单独检查无干扰基础场景：入睡帧床头+.8，后续帧+.5625、水平居中、无水平运动且保持自己的床位
该检查先在159的实际错误轨迹上检出下落和滑移，再用于修复候选；不由此推导完整睡眠行为或寻路收益

## 177定向修复验证

新合同明确HARD与初始空库存/药效，原版在独立世界设置并恢复难度，移植验证实际难度；两端额外记录实际非空物品数与药效数
原版选定集合增至12类，包含真实MaidPoiManager；移植额外核协调器和共享睡眠资源所有者的包内资源
检查器可加 `--require-paired-inputs --require-immediate-hurt-wake`：前者只限定已列明标量/家园/任务/语义布局及空物品/药效，后者核实际接受generic伤害即时醒来/清床与下一自然帧
旧176实际F/N各被后者检出失败，修复177的原版/F/N均通过，五机制共15只/3600帧完整；完整初始NBT和随机流等价仍为false
完整SNBT审阅还发现随机Rotation/SoundPackId、Fire默认值、列表/属性/库存编码、任务数据和两版附加字段差异；正式统计前需冻结可控输入并审查余项语义，不能把所选字段一致称为全状态一致

## 许可证

本工具原创代码沿用项目许可证；固定上游及复用只读agent各自保留原有来源与许可

## 五机制成对探索

`paired-contract.json` 与 `SleepMechanismGameTests` / HQA `SleepMechanismPortTests` 独立于旧三只女仆基础合同
显式设置 `TLMNH_SLEEP_PAIRED=true` 时，原版使用独立namespace `tlmnh_sleep_paired` 与 `sleep-paired-gametest` 工作目录；原模板另复制到该namespace，避免Forge按模板域过滤成零测试
五种机制为正常、占用后释放、不可达、睡后拆床、睡中受伤，每场一只全新自然实体观察240帧
受控占用者只用于提供场景输入，不替代被测女仆；移植使用真实Tile租约，原版使用实际床占用状态
原 `MaidPoiManager` 只注册未占用床头，故占用场景出生时实际POI数为0，其余为1；不伪造POI补齐旧断言

原版LevelTick与移植WorldTick末尾在已记录帧之后执行干预，同时保存即时睡姿/健康/床占用及后续自然轨迹
未入睡或未触发干预的记录保留，GameTest成功仅表示采集完成
新捕获还保存实际方块布局、属性、天气、难度及完整初始SNBT；`compare_mechanisms.py` 核选定类资源、原版首次定义、合同、全根F/N、自然tick、15只独立实体及HQA/JUnit，分别列出输入差异和各机制结果
运行参数见该检查器 `--help`；历史验证应指向该次冻结工具，不用新源码替换历史输入

176实际完成原版5例、完整F/N10例，共3600帧；难度原版EASY/移植HARD，其余已比较的标量、家园和语义布局一致，完整NBT/库存语义仍未准入
实际原版受伤立即醒来，F延迟10帧、N下一帧醒来；这些差异与首轮零测试、POI旧断言导致的3例失败分别保留，不把采集通过写成机制等价
完整B10、同随机流/跨版本物理、非劣效余量和统计结论仍为 `NOT_EVALUATED`
