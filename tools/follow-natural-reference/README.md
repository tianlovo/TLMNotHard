# 原版女仆自然跟随观察器

在独立 Minecraft 1.20.1 参考客户端中登记真正的原版 EntityMaid，让完整 Brain 和原生导航通过实际世界 tick 运行
本工具采集原版自身的变异，不提供移植版 AI 测评通过结论

## 背景

固定环境为 TLM `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6`、Forge 47.2.0、Java 17
女仆使用精确原类，没有子类、noAI 或手工调用 Brain/AI 方法
真实主人来自当前 IntegratedServer 的 PlayerList 和客户端连接，采集期间由受保护旁观临时切为创造模式，结束恢复
主人位置是脚本经真实服务端连接 teleport 输入的轨迹，不代表键盘操作

原日程 ALL 实际运行 WORK 活动
TaskIdle 的工作任务、core、随机走动、传感器和其他原活动全部保留；不会为得到相同轨迹删任务或每 tick 重置 RNG
三个场景是直行后停、直角转向、跨传送距离界，各三次全新女仆，共1800个实体观察帧
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

主人先在65×65的专用 STONE/空气平台上预热真实 chunk ticket，女仆在一个 LevelTick END 真实加入世界，下一 START 才记录 frame0
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

参考编译与身份准备已跑通，真实采样尚待验收
Python解析器/归一化测试只能验证这些工具函数，不是原版 AI 的替代 oracle
首次使用需保留构建、身份、新原始数据、A/A变异报告与失败记录，再决定后续采样方案

正常完成、取消、主人退出和服务端停止都会尝试删除测试女仆、逆序恢复自己的方块、恢复日时/天气/规则和原主人状态
原生 PlayerList.remove 在保存玩家之前发出退出事件，工具在该事件中优先恢复主人
清理未完成时不给 complete；客户端模式确认和 player.dat 持久化仍单独标未验证
硬杀进程、掉电、多文件原子回滚不在本工具保证内

## 维护者

[tianlovo](https://github.com/tianlovo)

## 参与贡献

保留原版完整活动和未固定随机源，不修补原始输出或用手算结果代替真实执行
修改合同、工具或依赖后重新取得身份和新采样，不覆盖旧基线
自然采集、组件重放、真实RC2和最终O/F/N测评分开准入

## 许可证

原创工具的最终分发许可随项目在正式发布前确定，调用的原版类及复用的 observer 保留原有来源和许可
