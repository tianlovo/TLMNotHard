# 原版女仆自然跟随观察器

在独立 Minecraft 1.20.1 参考客户端中登记真正的原版 EntityMaid，让完整 Brain 和原生导航通过实际世界 tick 运行
本工具采集原版自身的变异，不提供移植版 AI 测评通过结论

## 背景

固定环境为 TLM `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6`、Forge 47.2.0、Java 17
女仆使用精确原类，没有子类、noAI 或手工调用 Brain/AI 方法
真实主人来自当前 IntegratedServer 的 PlayerList 和客户端连接，采集期间由受保护旁观临时切为创造模式，结束恢复
主人位置是脚本经真实服务端连接 teleport 输入的轨迹，不代表键盘操作

原版开局魂符可能占据当前主手，准备阶段会保存原selected slot，切到现有空hotbar槽并发送真实carried-slot同步包
不删除、搬动或回填物品；没有空hotbar或副手非空时拒绝采集
采样每帧仍严格双手为空，结束切回原slot，并核9格/副手的实际ItemStack.matches与前后NBT摘要
status在真正客户端线程读取clientSelectedSlot和双手，不能把服务端发包直接冒充客户端已确认

原日程 ALL 实际运行 WORK 活动
TaskIdle 的工作任务、core、随机走动、传感器和其他原活动全部保留；不会为得到相同轨迹删任务或每 tick 重置 RNG
当前合同为 `FOLLOW_NATURAL_WATER_O_V1`：封闭水道与水下一格高通道，各三次全新女仆，共1200个实体观察帧
历史平坦合同含直行后停、直角转向与跨传送距离界，保留为 `flat-contract-v1.json`；其旧样本使用配对冻结工具校验
世界 RNG、实体 RNG 和传感器初始相位不固定，三次观察不构成统计显著性证明

## 安装

复用已有独立原版 checkout、原动画参考 init、审批工具链锁和外部运行目录
设置 `TLMNH_FOLLOW_NATURAL_PROBE` 指向本目录，保留 `TLMNH_REFERENCE_PROBE`、`TLMNH_REFERENCE_HOME`、Git和Java17配置
仅传入本目录 `reference.init.gradle`，它会应用原 base init，不要再次叠加其他参考工具 init
任务为 `prepareFollowNaturalReferenceIdentity`、`followNaturalDefinitionAgentJar`
新清单写到外部 HOME 的 `identity/follow-natural-build.json`

新 init 关闭旧 reference、geometry、follow、move 捕获开关，使用独立 natural 开关和资源目录
复用 geometry 的只读首次定义 observer，但选定集合和新身份清单独立；原 base 身份仍单独绑定
选定类资源与首次定义摘要分列，不把它们扩称为整个 classpath 的保证

## 使用

```text
python tools/follow-natural-reference/control.py status
python tools/follow-natural-reference/control.py lab_open
python tools/follow-natural-reference/control.py capture_natural
python tools/follow-natural-reference/control.py cancel
python tools/follow-natural-reference/compare.py <capture.json.gz> --build-identity <follow-natural-build.json>
python tools/follow-natural-reference/test_compare.py
```

没有专用存档时使用 `lab_create`，已有存档不覆盖
控制根为外部 HOME 的 `client/tlmnh-follow-natural-reference`
就绪 marker 是 `TLMNH_FOLLOW_NATURAL_REFERENCE_READY`，捕获摘要 marker 是 `TLMNH_FOLLOW_NATURAL_REFERENCE_CAPTURE`
原始 JSON 以 gzip 留存，响应只返回相对路径、摘要和字节数
控制超时后保留同一运行进程与请求，先检查 status，不自动重启或并发重采

主人先在65×65的专用 STONE/空气平台及封闭水域中预热真实 chunk ticket，女仆在一个 LevelTick END 真实加入世界，下一 START 才记录 frame0
仍保存真实 spawn/frame0 起点，不回拨已自然发生的 tick 或位置
START 记录计划与真实调用后输入，END 记录实际主人与女仆、一次 LivingTick、memory、path节点、活动、控制器和属性
movement speed 的 base、最终值、modifier 与导航 speedModifier 分开，不能先假定 RC2 的0.3与原版一致

`NEAREST_VISIBLE_LIVING_ENTITIES` 只导出底层候选列表，不主动执行其可见性 predicate或改变缓存；候选不等于已可见
同 tick 内开始又停止的行为不能从 END 状态反推出调用次数
大位移不冒充 teleport 方法调用证据
未知 memory 值类型会留下失败材料，不编造默认值

比较器先核实际登记、在线主人、输入落地、自然 tick、身份和清理
再给出归一化后的逐帧首差和描述性数据，AA不相等记录为变异，既不擅自判AI失败，也不直接判测评通过
归一化仅处理已知参与者身份、坐标原点与时钟，不消除速度、运动或属性差异

## 开发状态

当前水域采样已取得完整 6 次、1200 帧，原版身份、输入、自然 tick、地形与玩家清理检查通过
水道为 153 个水源和 176 个玻璃块，低通道为 129 个水源和 212 个玻璃块；每轮核实际前后摘要，先清水后清外壁，未知变化拒绝覆盖
读取实际路径节点的类型与代价，不额外调用导航搜索；已观察到 WATER_BORDER，低通道也出现较大单帧位移，不能仅凭位移认定传送调用或穿越隧道
重复轨迹有变异；这是未固定随机源的观察，不准入严格逐帧等价、完整 F 或统计收益
Python解析器/归一化测试只能验证这些工具函数，不是原版 AI 的替代 oracle
首次使用需保留构建、身份、新原始数据、A/A变异报告与失败记录，再决定后续采样方案

正常完成、取消、主人退出和服务端停止都会尝试删除测试女仆、逆序恢复自己的方块、恢复日时/天气/规则和原主人状态
原生 PlayerList.remove 在保存玩家之前发出退出事件，工具在该事件中优先恢复主人
清理未完成时不给 complete；客户端模式确认和 player.dat 持久化仍单独标未验证
观察失败和各项清理失败分开记录，原始异常及cleanup每阶段异常完整保留在私有日志
观察失败但实际清理成功时cleanupComplete可以为true，complete仍为false
硬杀进程、掉电、多文件原子回滚不在本工具保证内

## 历史平坦合同的移植侧观察

Indev117 的独立 Horizon-QA 模组提供 NaturalFollowPortTests，按本合同的三个主人轨迹和各三次全新实体采集
观察器在真实 START/END 相位施加输入/记录结果，世界与实体 RNG 不重置；原版真实连接主人和移植标准 FakePlayer 分开标识
输出 `follow-natural-port.json.gz` 仍须与该轮 JSON/XML/process 结果共同核对，采集完成不是能力验收通过

`compare_port.py` 接收原始 O、原版身份清单、该次原版冻结工具目录、移植捕获、HQA 结果与预期玩法 SHA
历史源码发生变动时，使用对应冻结工具校验其全部输入 SHA，不放宽断言或用当前工具的文件差异否定历史数据
它核对原身份/输入/清理和移植九实体/1800帧/同一主人/自然年龄/输入/属性/移除/进程结果，再输出距离与组内/组间 RMS 描述
三个未固定随机重复、不同主人实现与不同游戏物理不准入严格等价或统计收益；完整 F/N、复杂场景、成本和预瞄对照继续独立验收

## 维护者

[tianlovo](https://github.com/tianlovo)

## 参与贡献

保留原版完整活动和未固定随机源，不修补原始输出或用手算结果代替真实执行
修改合同、工具或依赖后重新取得身份和新采样，不覆盖旧基线
旧样本用配对源码或冻结工具复算；更新检查器后，新增门禁另记分层复核，不能绕过输入摘要把旧身份冒充新版本
自然采集、组件重放、真实RC2和最终O/F/N测评分开准入

## 许可证

原创工具的最终分发许可随项目在正式发布前确定，调用的原版类及复用的 observer 保留原有来源和许可

## 水域结果与历史样本

Indev122 对应的是观察工具开发单元，生产候选仍为 Indev121；本批没有重新启动 GTNH 主双端
首轮六次数据虽采完并清理成功，完成判定仍写死九次，原结果保留为 inconclusive；按合同核运行数及总帧数后重新取得新样本，不改旧输出
两轮水域采集都需使用各自配对的采集器、身份清单与冻结工具，不能把旧样本声明改成新工具通过

真实新样本的 18 项解析/归一化/损坏检出检查通过；修正计数测试后的检查器用冻结身份做分层复核，不冒充该检查器重新编译过原版采集器
另外以实际样本验证错误预期次数、变化地形与未恢复地形均被拒绝，原版对照尚未推进到水域移植侧或完整 O/F/N 测评结论
