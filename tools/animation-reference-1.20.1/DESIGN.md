# 原版 TLM 1.20.1 动画对照实验

这是测试工具的实验入口，用于运行Minecraft1.20.1与该版本的上游原版TLM
GTNH模组仍以Minecraft1.7.10为目标，此目录的Java17探针不加入其源码集或分发JAR

小样本已在实际原版客户端采到两条路径的数据：汉服酒狐115根骨骼、Bedrock酒狐126根骨骼，各30帧，均无模型回退
实际加载清单仅有Minecraft、Forge与上游TLM，采集阶段上游源码保持不变
这只证明观测入口可用，A/A复现、A/B重放和故障检出尚无结果，不能作为动画等价性通过

## 选型与限制

计划先验证原版实际轨迹采集、移植版输入重放和分层姿态差分，再补两端实际渲染检查
两个世界同时播放后按录像帧号或截图直接判等价，会混入渲染时刻、随机数、物理步长及坐标系差异

动画回归中冻结数据、比较逐帧关节变换已有实践依据，但还须在本项目做小样本实验
参考：[Mohr-Daurat，SCA 2017](https://doi.org/10.1145/3099564.3106641)

结构化状态日志与首个分歧定位也有长期维护经验可参考
参考：[Riot，Fixing Divergences](https://www.riotgames.com/en/news/determinism-league-legends-fixing-divergences)

## 固定参照

| 项目 | 版本或约束 |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.2.0，与上游gradle.properties一致 |
| 上游TLM | 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6 |
| Java | 17，与上游工具链一致 |
| Gradle | 8.14.3，与上游wrapper一致 |
| 映射 | Parchment 2023.08.20-1.20.1，保持上游版本 |
| 运行模组 | Minecraft、Forge、上游TLM；实际清单仍需运行时核对 |

使用独立的上游源码checkout，原始源码保持不变，探针通过额外源码目录加入参考构建
reference.init.gradle把上游开发配置中的可选联动依赖移至compileOnly，保留原版编译API与必需库
映射数据另加NeoForge官方镜像作为下载来源，限制为org.parchmentmc.data组，不替换其他依赖来源

运行位置与缓存由本地环境设置，放在源码checkout之外；不提交游戏文件、图片、轨迹或本机绝对路径
初始化脚本需要TLMNH_REFERENCE_PROBE指向本目录，TLMNH_REFERENCE_HOME指向独立运行目录
在上游工程执行wrapper并通过--init-script指定reference.init.gradle即可应用，只用于参考构建

## 观测器协议

ReferenceObservationProbe在显式tlmnh.referenceProbe=true时读取游戏目录下tlmnh-reference/control/*.json
结果位于responses，采集数据位于captures；先写.tmp再改名为.json，每tick最多处理4条

| 请求 | 作用 |
| --- | --- |
| `{"command":"status"}` | 返回实际游戏版本、世界/玩家状态、界面及已加载模组清单 |
| `{"command":"create_lab"}` | 创建新的空气虚空世界，原生旁观模式；已有同名世界时拒绝覆盖 |
| `{"command":"spawn_case","model":"geckolib:winefox_hanfu"}` | 请求在该参考世界准备平台及指定原版女仆，返回UUID后再核对scene状态 |
| `{"command":"observe","entityUuid":"指定女仆UUID","frames":30}` | 采集该女仆接下来实际渲染的指定帧数，范围1至240 |
| `{"command":"shutdown"}` | 请求客户端正常退出 |

observe先返回waiting-render，完成后改为complete；60秒内未得到足够帧数则failed
场景请求返回时ready仍为false，必须等待status中的scene为ready；失败会保留错误
测试女仆关闭行为AI并固定观察位置，只验证动画采集，不把这些场景当作行为AI测评
数据包含原版当前模型、骨骼变换和可见性，Gecko另有片段、控制器状态与时间偏移
Bedrock记录额外四元数和偏移，不能只以欧拉角作为完整姿态

当前格式为observation-v0，仅记录render-post观测
allInputsCaptured和equivalenceVerified固定为false，不能把这份输出直接作为可重放轨迹或已通过的对照结果
尚需补齐真实更新标记、核心/头部/硬编码分层、完整输入和初始化状态，实验通过后再确定稳定协议

采样只读当前动画结果，渲染阶段缓存在有界列表，文件输出在客户端tick完成
采集器本身可能影响帧时刻，后续实验必须测量其影响并验证可重放性

首个汉服样本的相邻seekTime间隔约0.16至0.30 tick，主片段是jump，不能把该场景假定为固定步长的idle
上游样本含28个控制器，其中magic_casting在这30帧中保持STOPPED；不能仅凭与移植版控制器数量不同就判动画错误
原版字段本身可读取，但必须补齐输入、初始状态和阶段对应关系，才能进入可靠对照

## 扩大测试前的门槛

1. 在一个Gecko酒狐和一个Bedrock选择中取得所需字段，任何模型回退或空骨骼都失败
2. 原版相同输入在独立重建实例后至少重复3次，解释重复误差和非确定性
3. 移植版完整消费相同轨迹，并验证随机输出、更新步长、暂停和状态清理
4. 人为注入位移、时序或动作选择错误，自动比较必须失败并定位首个分歧
5. 用实际渲染检查补数值姿态无法覆盖的贴图、UV和GL状态问题

未实现的首版必需动作不算跳过通过，采样不稳定标不可判定，不自动扩大容差或覆盖基线
这些门槛通过后再扩展全部酒狐与其余内置模型
基线更新按版本留存，不要求用户日常逐个模型观察画面，也不为每次push新增整套GPU Actions
