# 原版 TLM 1.20.1 动画对照实验

这是测试工具的实验入口，用于运行Minecraft1.20.1与该版本的上游原版TLM
GTNH模组仍以Minecraft1.7.10为目标，此目录的Java17探针不加入其源码集或分发JAR

小样本已在实际原版客户端采到两条路径的数据：汉服酒狐115根骨骼、Bedrock酒狐126根骨骼，各30帧，均无模型回退
实际加载清单仅有Minecraft、Forge与上游TLM，采集阶段上游源码保持不变
受控原版A/A已通过：两种模型各重建三次、每次30步，四组逐字段对照均无差异
实际执行路径中的位移、时钟和动作选择故障也已自动检出
两个空手待机样本已完成CPU回放：Bedrock逐值一致，汉服存在启动时序分歧；通用输入与GPU检查仍未完成

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
| 运行模组 | 已核对实际ModList，仅Minecraft、Forge、上游TLM |

使用独立的上游源码checkout，探针通过额外源码目录加入参考构建
首次只读采集没有改动上游源码；受控实验另应用upstream-probe.patch，提供临时测试输入作用域和显式故障开关
默认none不注入故障，普通实体的输入保持原样；这些补丁只用于参考测试副本
reference.init.gradle把上游开发配置中的可选联动依赖移至compileOnly，保留原版编译API与必需库
映射数据另加NeoForge官方镜像作为下载来源，限制为org.parchmentmc.data组，不替换其他依赖来源

运行位置与缓存由本地环境设置，放在源码checkout之外；不提交游戏文件、图片、轨迹或本机绝对路径
初始化脚本需要TLMNH_REFERENCE_PROBE指向本目录，TLMNH_REFERENCE_HOME指向独立运行目录
在上游工程执行wrapper并通过--init-script指定reference.init.gradle即可应用，只用于参考构建

## 便携实验脚本

Python工具需要3.12或更高版本、Git和已经启动的参考客户端，代码不含开发者本机路径

| 环境变量 | 作用 |
| --- | --- |
| TLMNH_REFERENCE_PROBE | 本目录，供Gradle加入探针源码 |
| TLMNH_REFERENCE_HOME | 参考运行目录，与Gradle使用同一位置 |
| TLMNH_REFERENCE_UPSTREAM | 固定版本的上游checkout |
| TLMNH_REFERENCE_REPORTS | 可选的证据目录，默认在参考运行目录的evidence下 |
| TLMNH_REFERENCE_GIT | 可选的Git程序位置，默认使用PATH中的git |
| TLMNH_PORT_GRADLE | 可选的移植版Gradle程序，默认使用移植工程wrapper |

先在上游checkout对upstream-probe.patch执行git apply --check，通过后再应用
随后用上游wrapper加--init-script运行runClient，确保使用Java17
在本目录运行以下命令，A/A实验会重开已有参考世界；首次运行先通过control.py创建测试世界

```console
python control.py create_lab
python aa_experiment.py --keep-client
python fault_experiment.py
```

各脚本均支持--help；aa_experiment.py默认完成后正常退出客户端
组合执行时使用--keep-client，再由fault_experiment.py在完成后退出
compare_aa.py可单独比较两个已保存的受控样本，只判断原版重复性，不作跨版本等价判断

参考客户端正常退出后，将移植工程的构建环境切回Java25，在本目录运行python ab_experiment.py
它使用显式replay.init.gradle，将回放器临时加入test源码集，运行实际移植版CPU动画路径，不写入分发JAR
除两个正例外，还会在实际回放路径注入头部位移，并验证缺少输入字段时拒绝计算
每轮输出独立证据目录，保存实际输入哈希、加载类哈希、资源哈希与比较结果，ab-experiment.json指向最新结果

## 观测器协议

ReferenceObservationProbe在显式tlmnh.referenceProbe=true时读取游戏目录下tlmnh-reference/control/*.json
结果位于responses，采集数据位于captures；先写.tmp再改名为.json，每tick最多处理4条

| 请求 | 作用 |
| --- | --- |
| `{"command":"status"}` | 返回实际游戏版本、世界/玩家状态、界面及已加载模组清单 |
| `{"command":"create_lab"}` | 创建新的空气虚空世界，原生旁观模式；已有同名世界时拒绝覆盖 |
| `{"command":"open_lab"}` | 重开已经创建的参考世界 |
| `{"command":"clear_scene"}` | 请求清理带实验标记的女仆，另核对场景状态及maidCount |
| `{"command":"reload"}` | 场景清空后异步重载资源，完成后才允许受控采样 |
| `{"command":"spawn_case","model":"geckolib:winefox_hanfu"}` | 请求在该参考世界准备平台及指定原版女仆，返回UUID后再核对scene状态 |
| `{"command":"observe","entityUuid":"指定女仆UUID","frames":30}` | 采集该女仆接下来实际渲染的指定帧数，范围1至240 |
| `{"command":"sample_case","model":"geckolib:winefox_hanfu","frames":30,"seed":424242,"fault":"none"}` | 每次清场并重载后，用新实体从首次绘制开始受控采样 |
| `{"command":"shutdown"}` | 请求客户端正常退出 |

observe和sample_case先返回waiting-render，reload先返回waiting-reload，完成后才改为complete
60秒内未得到足够帧数则failed，客户端入口的单次等待另有上限
场景请求返回时ready仍为false，必须等待status中的scene为ready；失败会保留错误
测试女仆关闭行为AI并固定观察位置，只验证动画采集，不把这些场景当作行为AI测评
数据包含原版当前模型、骨骼变换和可见性，Gecko另有片段、控制器状态与时间偏移
Bedrock记录额外四元数和偏移，不能只以欧拉角作为完整姿态

当前格式为observation-v0；普通观测记录render-post，受控Gecko另存核心、头部和最终骨骼
allInputsCaptured和equivalenceVerified固定为false，不能把普通观测当作任意游戏轨迹的完整重放数据
受控模式另标记controlled-idle-v0，固定UUID、随机初始状态、tick、partial tick、落地与头部朝向
每次必须清理旧实体并重载资源，临时实体字段在绘制结束后恢复；采样步不等于现实播放帧时长
受控样本的inputs另标记controlled-empty-idle-v1，记录实际状态、查询值和绘制参数
只审查两个空手待机夹具，拒绝缺字段、未审查的活动片段依赖和额外动作层，不假定未知值为0
完整游戏轨迹、随机动作和其他状态仍未覆盖

资源核对按上游真实加载路径执行：唯一原版default pack的解包文件，以及常规资源管理器中的默认Gecko动画
多模型包时拒绝使用这个受限合同，防止把其他包的同名模型当原版基线
移植版资源必须与固定Git字节相同；原版有效JSON允许可复算的LF/CRLF换行变体，贴图等二进制必须逐字节一致
不忽略任意JSON内容变化，也不把资源相同当作贴图和UV已经正确绘制

采样只读当前动画结果，渲染阶段缓存在有界列表，文件输出在客户端tick完成
采集器本身可能影响帧时刻，后续实验必须测量其影响并验证可重放性

首个汉服样本的相邻seekTime间隔约0.16至0.30 tick，主片段是jump，不能把该场景假定为固定步长的idle
上游样本含28个控制器，其中magic_casting在这30帧中保持STOPPED；不能仅凭与移植版控制器数量不同就判动画错误
原版字段本身可读取，但必须补齐输入、初始状态和阶段对应关系，才能进入可靠对照

## 已完成的小样本验证

| 实验 | 实际结果 | 结论范围 |
| --- | --- | --- |
| 原版A/A | 两模型各3次、每次30步，4组对照差异0 | 这两个受控用例可重复，不代表全部模型和状态 |
| 排除全冻结假通过 | 汉服10根、Bedrock3根骨骼随采样步变化 | 并非只比较了不动的骨骼 |
| 头部位移故障 | 首个分歧frames[0].bones.Head.translation[0]，0变为0.25 | 检出实际绘制前的骨骼偏移 |
| 时钟故障 | 首步期望0、实际1，被内部时钟合同拒绝 | 检出时间输入与引擎推进不一致 |
| 片段选择故障 | 首个分歧frames[0].controllers[8].clip，idle变为walk | 检出实际主控制器选错片段 |
| Bedrock CPU回放 | 126骨骼、30步，输入与姿态逐值一致 | 仅该空手待机夹具的局部骨骼 |
| 汉服CPU回放 | 首个分歧frames[0].controllers[0].state，TRANSITIONING与RUNNING不同 | 定位启动时序，尚未修正 |
| 移植版实际位移故障 | 首个分歧frames[0].bones.Head.translation[0]，0变为0.25 | 没有编辑保存后的姿态JSON |
| 缺输入 | 缺groundSpeed时实际Java回放拒绝执行 | 不以默认值填补未采字段 |

故障通过上游实际执行路径的测试钩子注入，未修改保存后的JSON来冒充端到端检出
上述结果只支持这两个有界用例中的机器对照；汉服分歧仍待修正，没有验证贴图、UV、GL状态或所有随机和物理组合
比较器使用原始精确数值，不按移植结果放宽容差；跨平台误差预算与四元数/矩阵归一化仍需另行校准
原版magic_casting缺于移植版时，只在每一步均STOPPED、无片段且时间参数未动的夹具中投影掉该层，否则不可判定
目前不批准扩大到全部模型批量验收

## 扩大测试前的门槛

1. 在一个Gecko酒狐和一个Bedrock选择中取得所需字段，任何模型回退或空骨骼都失败
2. 原版相同输入在独立重建实例后至少重复3次，解释重复误差和非确定性
3. 移植版完整消费相同轨迹，并验证随机输出、更新步长、暂停和状态清理
4. 人为注入位移、时序或动作选择错误，自动比较必须失败并定位首个分歧
5. 用实际渲染检查补数值姿态无法覆盖的贴图、UV和GL状态问题

未实现的首版必需动作不算跳过通过，采样不稳定标不可判定，不自动扩大容差或覆盖基线
这些门槛通过后再扩展全部酒狐与其余内置模型
基线更新按版本留存，不要求用户日常逐个模型观察画面，也不为每次push新增整套GPU Actions
