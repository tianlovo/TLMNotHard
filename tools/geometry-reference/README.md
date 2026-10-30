# 原版几何独立对照（Indev）

本工具比较两个内置酒狐模型的静态几何，不使用移植版几何函数生成预期结果
当前合同为 `native-bind-geometry-v1`，Java 部分尚待编译与真实客户端执行
已通过的 Python 合成测试只检查比较器及输入边界，不是几何实验通过的证据

## 范围

原版在 MC 1.20.1 客户端线程上，从实际资源管理器读取模型并连续重建三次
Gecko 调用原版 `IGeoRenderer.renderRecursively`，Bedrock 构造原版 `BedrockModel`，调用其继承自 `AbstractBedrockEntityModel` 的 `renderToBuffer`
记录原生 `VertexConsumer` 输出的位置、法线、UV，以及包含匿名 cube 子节点的静态骨骼树
没有另写几何或父链矩阵公式，也没有修改原版动画控制器

移植端在独立 JVM 中，每次新建 `BuiltinMaidModels`，执行实际生产 `BuiltinMaidModel.renderGeometry`
两侧只共用公开字段的序列化辅助类；预期顶点由原版方法独立计算
不把原版测得的最终骨骼覆盖到移植模型，避免遮住静态 pivot 或初始旋转的解析差异

坐标转换已在 `contract.json` 固定：Gecko 原生位置转换为 `(-x, 1.5-y, z)`，法线为 `(-nx, -ny, nz)`；Bedrock 保持原坐标
禁止用拟合、顶点重排或自动翻轴降低差异
先要求各自三次新建输出完全稳定，再逐项比较静态树和完整顺序顶点流
位置、法线、UV 的绝对误差阈值分别为 `1e-5`、`1e-5`、`1e-6`，保留各项最大误差和第一个差异

负例由移植端真实执行路径产生：调用生产几何前额外镜像 X，或在实际提交 sink 偏移 U；最后重新执行无故障版本验证恢复
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

新 init 另建一个只读 premain agent，记录选定几何类与新探针的第一次实际类定义，并与实际编译输出逐项核对
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
  --evidence "$NewGeometryEvidenceDirectory"
```

编排器依次采集两个模型，对每个模型执行生产正常、镜像、UV偏移及恢复
每个 Java 进程内部同样重建三次；移植端也通过独立 premain agent 核对实际生产类定义
单独保留原始捕获、Java stdout/stderr、比较报告和总报告
输出目录必须是新目录，旧失败、拒绝和基线不被覆盖
本工具不自动关闭客户端；主开发入口检查结果后正常关闭，并保留进程记录

## 判定与边界

`PASS_BOUNDED_BIND_GEOMETRY_EXPERIMENT` 要求两个模型的正常与恢复匹配，镜像产生位置差异，UV负例产生独立UV差异，且所有 A/A 与身份检查通过
有效流存在顶点、静态树或数值差异时报告 `GEOMETRY_DIFFERENCE`；输入缺失、身份未知、不稳定或不支持时报告 `INCONCLUSIVE`
CLI 只有整个实验通过才返回退出码 0

它只能检验两个模型的静态解析、原生父链变换、初始 cube 旋转及顺序顶点/法线/UV
它不验证动态动画最终父链、世界 renderer、实体朝向绑定、光照/颜色/材质、GPU、shader、全部模型或跨驱动像素等价
两侧共享资源和代码来源，不能发现上游和移植版恰好共同保留的算法缺陷
现有动画合同和已通过的 FBO 矩阵实验仍是独立证据，不能用本结果替换它们

只运行比较器合成测试：

```powershell
python -B -m unittest discover -s tools/geometry-reference -p test_compare.py -v
```
