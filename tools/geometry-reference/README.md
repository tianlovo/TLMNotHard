# 原版几何独立对照（Indev）

本工具比较两个内置酒狐模型的静态几何，不使用移植版几何函数生成预期结果
当前合同为 `native-bind-geometry-v2`，36版和恢复原版挂树流程后的37版均有真实有界实验记录
已通过的 Python 合成测试只检查比较器及输入边界，不是几何实验通过的证据

旧 v1 四轮报告保持原判：第四轮 Bedrock 的有序数值及负例通过，Gecko 因兄弟骨骼发射顺序不同而比较失败
只读分块诊断不能把旧失败改成通过；v2 使用新的 schema、合同摘要和证据目录，旧捕获不能直接转换或重新贴标签
36版v2记录的Gecko几何值匹配及17组顺序差异保留，不修改该基准或参考端合同

37版重新执行后，Gecko 的根、兄弟与完整顶点提交次序均匹配，原17组兄弟次序差异归零
该次捕获包含原版115骨骼、11304顶点，最大位置误差为 `5.960464477539062e-7`，最大法线误差为 `1.4901161193847656e-7`，UV误差为0
Bedrock 的2472个有序顶点在位置、法线和UV上均为0差异
两模型都通过三次新建的 A/A、plain/traced 中性检查、镜像、UV偏移、面内换角及恢复检查
这些结果证明当前静态合同可自动对照，不能据此推断材质、动画中的动态输入或完整世界画面一致

现有动画冻结包仍承担日常 CPU 回归：恢复挂树流程后，在已有依赖缓存的独立离线克隆中，6正例和7个Java负例再次通过
本工具的几何捕获尚未整理成可分发的独立离线冻结包；原始运行身份可能含本机依赖路径，留在私有证据目录，不能直接提交

## 范围

原版在 MC 1.20.1 客户端线程上，从实际加载的唯一默认模型包读取模型并连续重建三次
Gecko 调用原版 `IGeoRenderer.renderRecursively`，Bedrock 构造原版 `BedrockModel`，调用其继承自 `AbstractBedrockEntityModel` 的 `renderToBuffer`
记录原生 `VertexConsumer` 输出的位置、法线、UV，以及包含匿名 cube 子节点的静态骨骼树
Gecko 在真正的 `renderCubesOfBone` 边界读取当前骨骼名和实际 `GeoMesh.faces(i)`，通过原方法继续计算顶点
每个实际写出顶点记录 `(bone,cube,face,corner)` 和 `drawIndex`，不通过推测 DFS 给顶点猜骨骼
没有另写几何或父链矩阵公式，也没有修改原版动画控制器

移植端在独立 JVM 中，每次新建 `BuiltinMaidModels`，执行实际生产 `BuiltinMaidModel.renderGeometry`
两侧只共用公开字段的序列化辅助类；预期顶点由原版方法独立计算
标签辅助类只读取实际骨骼回调和面 mask，不计算预期坐标或父链矩阵
不把原版测得的最终骨骼覆盖到移植模型，避免遮住静态 pivot 或初始旋转的解析差异

移植版新增可选的只读 `GeometryBoneObserver`：普通 sink 不实现该接口时不复制 mesh 数据，仍走原有计算
每个样本另建一个无 observer 的模型执行相同输入，保留 `plainVertices` 与 traced `vertices`，要求逐值相等
`rawAAStable` 与 `observerNeutralityVerified` 分开记录，比较器分别复查三次原始输出稳定及观察中性；三次新模型不是三个独立参考 JVM

坐标转换已在 `contract.json` 固定：Gecko 原生位置转换为 `(-x, 1.5-y, z)`，法线为 `(-nx, -ny, nz)`；Bedrock 保持原坐标
两模型的几何与贴图摘要固定于同一上游来源，两侧各自必须满足；不能通过一起改资源生成新的比较基线
`geometrySha256` 保持固定 Git blob 的 canonical LF 字节摘要，移植端必须严格匹配
参考端几何 JSON 只接受合同中列出的 LF 和完整 CRLF 两个摘要；CRLF 摘要由已核对 `builtin-assets-provenance.json` 的 canonical 字节逐个将 LF 替换为 CRLF 推导
这项规则只处理参考默认包解压后已实测的换行差异，不接受混合换行、额外空白或其他 JSON 改动；贴图仍严格匹配原摘要
原始捕获和比较报告保留参考端实际 SHA、移植端实际 SHA 及 canonical SHA，不把实际 CRLF 摘要改写成 LF 摘要，也不修改资源、旧捕获或旧实验判定
合同摘要随规则变更而改变，后续实验须重新构建身份并重启采集，旧捕获不能直接套用新合同
禁止拟合、全局排序坐标或自动翻轴，原始提交流保持原顺序
Gecko 只按实际语义 key 对应顶点，必须唯一且完整覆盖实际 mesh 的全部面，面内四角及同骨骼 cube/face 的次序不放宽
父子关系和其他静态字段仍逐项比较，根与兄弟的成员集合用于几何对照，原始列表及全局 `drawIndex` 差异单独报告
Bedrock 保留已经使用的有序数值路径，不伪造骨骼标签
当前 Gecko 静态合同要求全部节点可见且单位缩放，未支持的隐藏状态或尺度返回不可判定
先要求各自三次新建输出完全稳定，再逐项比较静态树和顶点数值
位置、法线、UV 的绝对误差阈值分别为 `1e-5`、`1e-5`、`1e-6`，保留各项最大误差和第一个差异

负例由移植端真实执行路径产生：调用生产几何前额外镜像 X、在实际提交 sink 偏移 U，或把实际写出的一面四顶点中的第0和第1个交换；最后重新执行无故障版本验证恢复
`corner_swap` 在 sink 写出前执行，corner 标签按最终写出位置生成，不把 key 与数值一起交换来掩盖故障
比较器不根据 fault 标签决定匹配结果，不修改已保存的原版或移植报告来制造负例

## 原版接入

沿用已批准的原版 checkout、Java 17、Gradle 8.14.3、依赖缓存和工具链锁
先应用既有 `animation-reference-1.20.1/reference.init.gradle`，再应用本目录的 `reference.init.gradle`
新 init 会关闭既有动画控制轮询，启用独立几何控制轮询；不会修改既有源码、20类清单、补丁、样本或 bundle

下列变量由本机开发入口提供，运行目录为源码根目录；原版 checkout 与运行目录均应在源码仓库外：

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

使用同样参数运行 `runClient`，通过项目现有后台进程管理入口启动并记录 PID、日志及结束状态
不要用普通 GTNH Gradle 构建原版，也不要向实际游玩实例安装探针
原版 Java 探针由注解自动注册，不需要额外上游补丁

新 init 另建一个只读 premain agent，记录选定几何类与新探针的第一次实际类定义
原版逐项核对实际加载类的 resource 字节等于编译输出，同时独立保留变换后的首次 definition 摘要、次数、loader和阶段
Forge 类加载器会转换类，definition 摘要可以不同于 resource 和 compiled；这份记录不证明全部类转换与编译字节等价
移植端独立 JVM 没有该类加载转换，仍要求选定生产类 definition 摘要严格等于编译输出
它与原版动画身份 agent 使用不同类名和状态，不重置原版 agent 的选定集合
证据包含原版已有批准构建身份、独立几何构建身份及实际资源摘要；身份范围仍是选定类，不是整个 classpath 的等价证明

这是独立的几何 profile：原版进程严格只允许这两个已绑定 JAR 摘要和选定类列表的被动观察器，要求实际 Java 17 与批准构建一致，并验证动态 attach 已关闭
既有动画 v1 的运行身份门禁要求恰好一个 agent；若在本 profile 手动调用它，会拒绝，不能借其 VERIFIED 标签证明本次运行
本次报告只锚定既有批准 build provenance 与新几何类定义，明确 `baseReferenceRuntimeVerified=false`；旧 v1 清单、样本及其原有身份没有改变

## 准备原版场景

所有请求都写入原版 game directory 下独立的 `tlmnh-geometry-reference`
使用原有虚空实验世界，捕获要求玩家实际处于无敌旁观模式
首次创建与重新打开分别使用以下命令，已有世界不能被创建操作覆盖：

```powershell
python tools/geometry-reference/control.py --game-dir "$ReferenceHome/client" status
python tools/geometry-reference/control.py --game-dir "$ReferenceHome/client" lab_open
# 仅首次不存在实验世界时使用 lab_create
```

等待 `status` 显示 `worldLoaded=true`、`spectator=true` 后采集
本实验不生成女仆或改变玩家模式；只在受保护客户端中调用原生几何数学入口，不声称捕获完整世界绘制

```powershell
python tools/geometry-reference/control.py --game-dir "$ReferenceHome/client" `
  capture_bind --model geckolib:winefox_hanfu
```

每次请求固定三次重建，输出新文件，不覆盖旧证据
非有限顶点、未知法线分支、身份不符或安全条件不满足返回不可判定

## 准备移植端并执行对照

原版构建完成后，使用其独立 agent JAR 准备移植端启动清单
这一步在源码根目录执行，使用项目既有 Java 25 / Java 8 字节码工具链：

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

编排器依次采集两个模型，对每个模型执行生产正常、镜像、UV偏移、corner_swap及恢复
每个 Java 进程内部同样重建三次，每次各有 plain/traced 新模型；移植端也通过独立 premain agent 核对实际生产类定义
新增的标签辅助类、两侧 sink、原版 renderer wrapper 和生产观察接口都列入各自编译清单及实际首次定义采样
共享 helper 和移植工具维持 Java8 字节码，只有原版和独立 agent 使用 Java17

### 移植端 fastutil 来源

恢复上游树容器后，`RawBoneGroup`、`RawGeometryTree`及实际依赖的`HashCommon`、`Object2ObjectOpenHashMap`、其`$MapIterator`均为必需身份项
`prepareGeometryReplay`要求Gradle实际解析出唯一无classifier的`it.unimi.dsi:fastutil:8.5.18`，期望外部类字节从该JAR条目读取，不从项目class输出目录寻找
清单的`compiledClasses`延用选定期望字节集合：项目类来自编译输出，外部类同时由`externalClasses`标记JAR条目，并由`externalArtifacts`记录解析坐标、实际路径及整个JAR摘要
运行时外部类的第一次definition与resource字节均须匹配条目，实际CodeSource和resource的JarURLConnection都须指向这个已解析JAR；其他生产类仍严格核对编译字节
缺类、换版本、换JAR来源或resource落到其他位置均不可判定，不默认采用classpath中的另一份fastutil
此来源验证只适用于独立CPU重放，不能替代实际RC2客户端/服务端的库加载检查

依赖实际路径保存在仓库外的私有manifest和运行证据，不能把这些文件直接提交到公开仓库
参考producer、契约、启动时身份和旧报告不因这一移植端扩展而改变；重新生成port launcher后使用新证据目录
单独保留原始捕获、Java stdout/stderr、比较报告和总报告
输出目录必须是新目录，旧失败、拒绝和基线不被覆盖
本工具不自动关闭客户端；主开发入口检查结果后正常关闭，并保留进程记录

## 判定与边界

`PASS_BOUNDED_BIND_GEOMETRY_V2_EXPERIMENT` 要求两个模型的正常与恢复匹配，镜像与corner_swap产生位置差异，UV负例产生独立UV差异，且所有 A/A、观察中性和身份检查通过
有效流存在顶点、静态树或数值差异时报告 `GEOMETRY_DIFFERENCE`；输入缺失、身份未知、不稳定或不支持时报告 `INCONCLUSIVE`
CLI 只有整个实验通过才返回退出码 0

恢复原版挂树流程后的回归使用上例的 `--require-submission-order`
默认 v2 仍只按语义几何判定，避免改写旧实验的含义；此选项另设 `order_policy`，要求正常和恢复的 Gecko 根、兄弟及实际顶点 key 提交次序全部一致
Bedrock 只沿用已匹配的有序顶点流，不补造语义骨骼 key；缺失或未知次序证据不能通过
次序策略不会修改原捕获、几何比较结果或材质验收标志

几何数值通过不表示提交次序相同，更不表示透明、深度竞争或材质顺序通过
`drawOrder` 保留实际差异，`orderDependentRenderingVerified` 始终为 false；二值 alpha 贴图也可能存在共面重叠，不能据此认为换序无影响

它只能检验两个模型的静态解析、原生父链变换、初始 cube 旋转及顺序顶点/法线/UV
它不验证动态动画最终父链、世界 renderer、实体朝向绑定、光照/颜色/材质、GPU、shader、全部模型或跨驱动像素等价
两侧共享资源和代码来源，不能发现上游和移植版恰好共同保留的算法缺陷
现有动画合同和已通过的 FBO 矩阵实验仍是独立证据，不能用本结果替换它们

只运行比较器合成测试：

```powershell
python -B -m unittest discover -s tools/geometry-reference -p test_compare.py -v
```
