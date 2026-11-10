# 原版几何独立对照（Indev）

本工具独立比对两个内置酒狐模型的静态几何输出，不使用移植版几何函数来生成预期参照。
现行合同为 `native-bind-geometry-v2`，Indev36 版以及恢复原版挂树流程后的 Indev37 版均留有真实有界实验记录。
已通过的 Python 合成单测仅检验比较器逻辑与输入边界，本身不构成几何实验通过的证据。

早期 `v1` 四轮报告维持原判：第四轮中 Bedrock 模型的有序顶点数值及故障负例全部通过，而 Gecko 模型因兄弟骨骼发射次序差异比较失败。
只读分块诊断保持各版本证据独立；`v2` 采用独立的 schema、合同摘要与证据目录，历史 `v1` 采样作为早期基线分别归档。
Indev36 版 `v2` 记录下的 Gecko 几何数值一致与 17 组兄弟骨骼次序差异原样保留，不修改当时基准或参考端合同。

Indev37 版恢复挂树流程后重新执行对照，Gecko 模型的根骨骼、兄弟骨骼及完整顶点提交流次序全部对齐，原有的 17 组兄弟次序差异归零。
当轮原版采样共含 115 根骨骼、11304 个顶点，位置最大绝对误差为 `5.960464477539062e-7`，法线最大绝对误差为 `1.4901161193847656e-7`，UV 误差为 `0`；Bedrock 模型的 2472 个有序顶点在位置、法线与 UV 上均为零差异。
两个模型均通过三次新建实例的原版 A/A、`plain/traced` 观察中性检查，以及 X 轴镜像、UV 偏移、面内换角三类故障负例与恢复检查。
上述结果证明当前静态绑定几何合同具备自动对照能力，但不能据此外推材质绑定、动态动画输入或完整世界渲染画面一致。

既有的动画冻结包继续承担日常 CPU 回归：恢复挂树流程后，在具备本地依赖缓存的独立离线克隆中，6 个正例与 7 个 Java 语义负例再次全部通过。
本工具产出的几何原始采样尚未打包为可随仓分发的离线冻结包；运行身份记录中可能带有本机依赖缓存路径，因此保留在仓库外的私有证据目录中，不得直接提交入库。

## 范围

原版侧在 Minecraft 1.20.1 客户端主线程上，从唯一启用的默认模型包加载模型并连续新建三次实例：
- Gecko 路径调用原版 `IGeoRenderer.renderRecursively`；Bedrock 路径构造原版 `BedrockModel`，调用其继承自 `AbstractBedrockEntityModel` 的 `renderToBuffer`。
- 探针记录原生 `VertexConsumer` 写出的顶点位置、法线、UV，以及包含匿名 `cube` 子节点在内的完整静态骨骼树。
- Gecko 路径在真实的 `renderCubesOfBone` 边界读取当前骨骼名与实际 `GeoMesh.faces(i)`，随后交回原方法继续计算顶点；每个实际写出的顶点均带有精确的 `(bone, cube, face, corner)` 语义键与全局 `drawIndex`，不通过事后 DFS 猜测顶点归属骨骼，也不自行重写几何变换公式或修改原版动画控制器。

移植侧在独立 JVM 中运行，每轮新建 `BuiltinMaidModels` 并调用真实的生产方法 `BuiltinMaidModel.renderGeometry`。
原版与移植侧仅共享公开字段的数据序列化辅助类，预期顶点完全由原版方法独立算出。
语义标签辅助类只读取真实的骨骼回调与面掩码，不参与坐标或父级矩阵计算；同时绝不把原版测得的最终骨骼矩阵反向覆盖回移植模型，以免掩盖静态枢轴点或初始旋转角的解析偏差。

移植侧为此新增了可选的只读接口 `GeometryBoneObserver`：常规渲染 `sink` 未实现该接口时不会复制 `mesh` 数据，完全保持原有生产路径；实现该接口的对照采样则会为每个样本额外构建一个不带 `observer` 的普通模型执行同输入渲染，同时保存 `plainVertices` 与带追踪的 `vertices` 并校验两者逐值相等。
报告将 `rawAAStable` 与 `observerNeutralityVerified` 分列记录，比较器分别复核三次新建实例的原始输出稳定性与观察器零干扰；需注意同一进程内三次新建模型不等同于三次独立启动参考 JVM。

坐标系换算规则固定在 `contract.json` 中：Gecko 原生顶点位置按 `(-x, 1.5 - y, z)` 转换，法线按 `(-nx, -ny, nz)` 转换；Bedrock 模型保持原坐标不变。
两个受测模型的几何文件与贴图哈希锚定在同一固定上游提交，原版与移植侧必须同时满足，严禁通过同步篡改两侧资源来凑出新的比较基线。
`geometrySha256` 固定采用 Git 仓库内标准 LF 换行的字节摘要，移植侧资源必须与之逐字节匹配。
参考端由于默认内置包解压工具的平台行为，几何 JSON 仅允许匹配合同中预登记的标准 LF 摘要或纯 CRLF 推导摘要（后者由 `builtin-assets-provenance.json` 中已核对的标准 LF 字节逐行替换为 CRLF 精确算出）；该豁免仅覆盖已实测的纯换行符差异，拒绝混合换行、多余空白或任何 JSON 内容改动，且 PNG 贴图仍须逐字节匹配原始哈希。
原始捕获与对照报告同时保留参考端实测 SHA、移植端实测 SHA 与标准 LF SHA，既不把实测 CRLF 摘要伪造成 LF 摘要，也不回改资源文件、旧捕获数据或历史实验结论。合同哈希随规则变更同步更新，修改合同后须重建身份并重新采样，历史捕获不能直接套用新合同。

比较过程严禁做最小二乘拟合、全局顶点重排或自动翻转坐标轴，原始提交流顺序完整保留。
Gecko 路径严格按唯一语义键对齐顶点，要求不重不漏地覆盖实际 `mesh` 的全部面，且单个面内四角顺序及同一骨骼下 `cube/face` 的提交次序均不放宽；父子层级与其他静态字段逐项核对，根骨骼与兄弟骨骼集合用于几何比对，原始列表顺序与全局 `drawIndex` 差异则单独列报。
Bedrock 路径继续沿用有序顶点数值流比对，不额外编造骨骼语义键。
现行 Gecko 静态合同要求全部节点可见且处于单位缩放状态，遇到未纳入合同的隐藏节点或非单位缩放时直接返回不可判定。比较器先确认两侧各自三次新建输出完全稳定，再比对静态树结构与顶点数值；位置、法线、UV 的绝对误差上限分别为 `1e-5`、`1e-5`、`1e-6`，报告中完整保留各分量的最大误差与首个超限分歧。

故障负例全部经由移植端真实执行路径触发：在调用生产几何方法前对 X 轴做额外镜像、在实际顶点接收 `sink` 中偏移 U 坐标，或在写出单面四顶点前交换第 `0` 与第 `1` 个顶点，最后再跑一轮无故障正常渲染确认状态彻底恢复。
其中 `corner_swap` 发生在 `sink` 最终落点前，`corner` 序号按最终写出次序生成，避免把语义键跟着坐标一起交换而掩盖故障。比较器完全依据几何与次序结果判定，不读取外层 `fault` 名称走捷径，也不通过事后篡改 JSON 报告伪造负例。

## 原版接入

原版侧复用已审批的上游源码 checkout、Java 17、Gradle 8.14.3、本地依赖缓存与工具链锁。
启动时按顺序先后传入 `animation-reference-1.20.1/reference.init.gradle` 与本目录的 `reference.init.gradle`。本目录的 init 会关闭动画参考工具的控制轮询，开启独立的几何控制轮询，不会改动既有源码、20 类观察清单、补丁文件或动画离线冻结包。

以下环境变量由本机开发脚本在源码根目录注入，原版源码 checkout 与运行目录均须置于本仓库之外：

```powershell
$env:TLMNH_REFERENCE_PROBE = (Resolve-Path 'tools/animation-reference-1.20.1').Path
$env:TLMNH_GEOMETRY_TOOL = (Resolve-Path 'tools/geometry-reference').Path
$env:TLMNH_REFERENCE_HOME = $ReferenceHome
$env:TLMNH_GEOMETRY_HOME = $GeometryHome

& $ReferenceGradle --offline --no-daemon -p $UpstreamCheckout `
  --init-script "$env:TLMNH_REFERENCE_PROBE/reference.init.gradle" `
  --init-script "$env:TLMNH_GEOMETRY_TOOL/reference.init.gradle" `
  prepareGeometryReferenceIdentity
```

随后以相同参数执行 `runClient`，通过项目统一的进程管理入口启动并记录 PID、运行日志与退出码。切勿使用 GTNH 工程的 Gradle Wrapper 构建原版工程，也严禁向日常游玩的客户端实例挂载测试探针。原版侧 Java 探针通过 Forge 注解自动注册，无需额外打上游源码补丁。

本目录的 init 会额外构建一个独立的只读 `premain` agent，用于记录选定几何核心类与几何探针类的首次真实类定义。
原版侧逐类校验实际加载的 `resource` 字节与编译输出相等，同时独立保存经 Forge 类加载器变换后的首次 `definition` 字节摘要、加载次数、类加载器名称与生命周期阶段；移植侧独立 JVM 不经过 Forge 类变换，因此要求选定生产类的首次 `definition` 字节摘要与编译输出严格相等。
几何观察 agent 与动画参考身份 agent 使用各自独立的类名与静态状态，互不覆盖观察集合；输出证据同时锚定既有批准的基础构建身份、新增几何构建身份及实测资源摘要，覆盖范围仍限于选定类集合而非整套 classpath。

该运行模式构成独立的几何专用 profile：原版进程严格限定仅挂载这两个校验过 JAR 哈希与观察列表的被动 agent，强制要求实际运行在批准的 Java 17 上并确认动态 `attach` 已禁用。由于原版动画 `v1` 身份门禁要求进程内恰好只存在单个 agent，在此几何 profile 下若强行调用动画门禁会直接被拒绝，防止借用动画 `VERIFIED` 标签替本次运行背书；几何报告中会显式标注 `baseReferenceRuntimeVerified=false`，历史 `v1` 动画清单与样本保持原样。

## 准备原版场景

所有控制指令均写入原版运行目录下独立的 `tlmnh-geometry-reference` 子目录。
实验复用既有虚空测试世界，采样前强制核验玩家处于无敌旁观模式。首次建档与后续重开分别执行以下命令，已有同名存档时建档命令会拒绝覆盖：

```powershell
python tools/geometry-reference/control.py --game-dir "$ReferenceHome/client" status
python tools/geometry-reference/control.py --game-dir "$ReferenceHome/client" lab_open
# 仅首次不存在实验世界时使用 lab_create
```

确认 `status` 返回 `worldLoaded=true` 且 `spectator=true` 后即可发起采样。该操作不会在世界中生成女仆实体或切换玩家游戏模式，仅在受保护的客户端线程上调用原生几何计算入口，不等同于完整世界渲染抓帧：

```powershell
python tools/geometry-reference/control.py --game-dir "$ReferenceHome/client" `
  capture_bind --model geckolib:winefox_hanfu
```

每次采样请求固定执行三次新建模型重建并写入新文件，不覆盖既有证据；遇到非有限浮点顶点、未知法线分支、构建身份不匹配或场景安全条件未满足时，统一返回不可判定。

## 准备移植端并执行对照

原版构建就绪后，复用其产出的独立 agent JAR 生成移植端重放启动清单。以下命令在移植工程根目录执行，沿用项目标准的 Java 25 运行时与 Java 8 目标字节码工具链：

```powershell
./gradlew.bat --offline --init-script tools/geometry-reference/replay.init.gradle `
  prepareGeometryReplay `
  "-PgeometryAgent=$GeometryHome/identity/geometry-definition-agent.jar" `
  "-PgeometryLauncher=$GeometryHome/port/launcher.json"

python tools/geometry-reference/experiment.py `
  --game-dir "$ReferenceHome/client" `
  --launcher "$GeometryHome/port/launcher.json" `
  --evidence "$NewGeometryEvidenceDirectory" `
  --require-submission-order
```

实验编排脚本会依次采集两个酒狐模型，并对每个模型顺序执行正常渲染、X 轴镜像、UV 偏移、`corner_swap` 及无故障恢复五组运行。
每个独立 Java 进程内部同样连续重建三次，每轮同时生成 `plain` 与 `traced` 两份输出，并由 `premain` agent 核对生产类的首次定义字节。新增的语义标签辅助类、两侧顶点 `sink`、原版渲染包装类与生产侧观察接口均纳入各自的编译摘要清单与首次定义采样；共享辅助类与移植侧重放代码维持 Java 8 字节码，仅原版探针与独立 agent 使用 Java 17 编译。

### 移植端 fastutil 来源

恢复上游树结构容器后，`RawBoneGroup`、`RawGeometryTree` 及其实际调用的 `HashCommon`、`Object2ObjectOpenHashMap` 与内部迭代器 `$MapIterator` 均被列为必需身份核验项。
`prepareGeometryReplay` 要求 Gradle 实际解析出唯一且不带 `classifier` 的 `it.unimi.dsi:fastutil:8.5.18` 工件，外部类的期望字节直接从该 JAR 条目中提取，而不在项目自身的 `classes` 输出目录中查找。
启动清单中的 `compiledClasses` 继续保存全部选定类的期望字节摘要：项目自有类取自编译输出目录，外部依赖类则同时通过 `externalClasses` 登记 JAR 内部条目路径，并在 `externalArtifacts` 中记录 Maven 坐标、本地解析路径与整包 SHA-256。
运行时，这些外部类的首次 `definition` 字节与 `resource` 字节均须匹配期望条目，且实际的 `CodeSource` 与资源 `JarURLConnection` 都必须指向同一个已登记的 `fastutil` JAR；项目自有生产类则继续严格核对编译字节。一旦出现缺类、版本不符、来源 JAR 漂移或资源落入其他路径，立即判定为不可判定，绝不静默回退到 classpath 上的其他 `fastutil` 副本。该依赖来源校验仅覆盖独立 JVM 下的 CPU 几何重放，不能替代真实 GTNH 客户端与服务端的运行时库加载检查。

由于启动清单与运行证据内记录了本机缓存的实际文件路径，这些文件统一保存在仓库外的私有目录中，严禁直接提交至公开仓库。
参考端采集器、合同文本、启动身份锁与历史报告均不因移植端新增 `fastutil` 校验而改变；每次重新生成 `launcher.json` 后须指定全新的证据输出目录，分别归档原始捕获、Java 标准输出/错误流、单次比对报告与汇总报告。本工具执行完毕后不会自动关闭参考客户端，由外层主控在核实结果后正常发起停机并保存进程记录。

## 判定与边界

总判定为 `PASS_BOUNDED_BIND_GEOMETRY_V2_EXPERIMENT` 需同时满足：两个模型的正常轮与恢复轮全部匹配，X 轴镜像与 `corner_swap` 准确触发位置分歧，UV 偏移准确触发独立的 UV 分歧，且全部原版/移植端 A/A、观察器中性与类身份校验均通过。
若有效顶点流存在顶点数量、静态树结构或数值超差，报告 `GEOMETRY_DIFFERENCE`；若输入缺失、身份不符、重复采样不稳定或触发未支持状态，则报告 `INCONCLUSIVE`。命令行仅在整套实验全部通过时返回退出码 `0`。

恢复原版挂树流程后的回归测试须显式附带上例中的 `--require-submission-order` 参数。
不加该参数时，`v2` 默认仍仅按语义键核对几何数值，以免改变历史 `36` 版报告的判定口径；开启该参数后会额外写入 `order_policy`，强制要求正常轮与恢复轮中 Gecko 模型的根骨骼、兄弟骨骼及实际顶点语义键提交流次序完全一致。Bedrock 模型继续沿用既有的有序顶点流比对，不额外补造骨骼语义键；凡缺失或无法确认次序证据的情况一律不予通过。开启次序门禁不会篡改原始捕获文件、基础几何数值比对结果或材质验收标志。

静态几何数值比对通过不自动代表提交流次序一致，更不等同于半透明混合、深度缓冲竞争或材质渲染顺序已通过验收；报告中的 `drawOrder` 如实保留次序差异，`orderDependentRenderingVerified` 始终维持 `false`（即便使用二值 alpha 贴图，共面重叠区域仍可能受绘制先后影响）。

综上，本工具专注于两个内置酒狐模型的静态 JSON 解析、原生父子骨骼链变换、初始 `cube` 旋转以及有序顶点位置/法线/UV 计算；动态动画驱动下的矩阵链与实体渲染在后续管线中独立处理。既有的动画冻结包回归与无光影离屏 FBO 矩阵实验构成独立证据链，形成完整的几何与渲染验证体系。

单独运行比较器合成单测可使用以下命令：

```powershell
python -B -m unittest discover -s tools/geometry-reference -p test_compare.py -v
```
