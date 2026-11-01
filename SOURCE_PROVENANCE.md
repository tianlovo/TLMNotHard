# 上游移植记录

本页按移植阶段记录来源与改动，早期段落中的待办保留当时范围；当前实现和实际验收状态统一见 [Indev 进度](PROGRESS.md)

固定上游为 TartaricAcid/TouhouLittleMaid 的 1.20 分支，提交 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6，
代码许可见 licenses/TouhouLittleMaid-MIT.txt

| 工程路径，均位于 src/main/java/ | 上游同路径 | 移植与改动 |
| --- | --- | --- |
| com/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid.java | 是 | 将 TamableAnimal 和 SynchedEntityData 转为 1.7.10 EntityTameable 与 DataWatcher，保留默认模型 ID、任务 ID、日程、经验、饥饿、好感和 NBT 键；装备交给原版实体存储，副手单独保存与同步，基础背包开放六格；接入蛋糕首次驯服、主人界面、潜行空手坐姿、独立交互适配器与单一跟随任务入口，完整生活和战斗仍待接入 |
| com/github/tartaricacid/touhoulittlemaid/entity/ai/brain/MaidSchedule.java | 是 | 保留 DAY、NIGHT、ALL，移除高版本专用 EntityDataSerializer，由实体字节字段同步 |
| com/github/tartaricacid/touhoulittlemaid/entity/ai/brain/task/MaidFollowOwnerTask.java | 是 | 保留主人检查、动态半径、传送和呼吸分支顺序；通过纯 FollowOwnerActor 接口替代现代 Behavior 与世界调用，未包含外层 Brain 调度、状态和持续时间随机数 |

新增的背包视图、注册和测试命令位于 com.tianluoqaq.tlmnh，属于本移植工程的独立适配文件，没有复制其他研究候选的实现

## 核心交互与首批跟随

`com.tianluoqaq.tlmnh.common.interaction.MaidInteractions`参考同一固定TLM提交的四个MIT事件：
`SaddleMaidEvent`、`ApplyGoldenAppleEvent`、`ApplyPotionEffectEvent`、`GetExpBottleEvent`，保留版权、原路径和来源头
适配内容为1.7.10原生乘骑、服务端权限、药效及容器守恒；牛奶使用Forge治愈接口，保留不可治愈的自定义效果
金苹果药效核对MC1.20.1实际食品定义，不直接沿用1.7.10附魔金苹果数值
创造消耗同时核对1.20.1的Player交互恢复和Forge补丁：食品、饮品保留，兑换扣女仆经验但保留创造玻璃瓶
主人操作保留上游主手优先，RC2 Backhand已有当前槽切换和重复包抑制；副手蛋糕驯服仍可使用

`com.tianluoqaq.tlmnh.common.ai.MaidBrainCoordinator`与`MaidFollowController`为新增实现，
参考已读取的原版主人跟随约束和1.7.10原生任务、导航、移动及注视接口，没有复制其他行为框架
首批只有主人跟随、硬约束中断、感知/寻路预算、失败退避和卡住恢复，尚未实现完整战斗或生活仲裁
取消只释放仍属于自己的路径，不清外部攻击和使用物品状态；导航前检查原版ChunkCache所需的已加载区范围
该预检会在加载边缘保守停步；单元检查和适配器夹具不能替代真实移动、多人同步或原版benchmark

实际小视距测试暴露出感知距离64与导航缓存范围耦合会阻止短距离跟随
新增`MaidBoundedPathPlanner`将单次原版PathFinder预算限制为16，缓存预检使用同一预算加8，保留原Navigator执行路径
原生`canNavigate`及私有门、水选项通过缓存的Forge反射接口只读获取，MCP/SRG名称均核对固定1.7.10映射；适配失败显式拒绝
没有改动实体感知属性、服务器视距或强制加载区块；冷却继续保留最后一次路径结果与诊断，实际移动仍须由自然tick证明

## Bedrock 几何

以下 16 个原文件均来自上述固定提交，位于 `com/github/tartaricacid/simplebedrockmodel/client/bedrock/`，保留原包名、原说明和代码许可头

| 相对路径 | 适配内容 |
| --- | --- |
| pojo/BedrockModelPOJO.java、BedrockVersion.java、BonesItem.java、CubesItem.java、Description.java、FaceItem.java、FaceUVsItem.java、GeometryModelLegacy.java、GeometryModelNew.java | 保留格式解析、骨骼、镜像继承与逐面 UV；版本比较改为 Forge 自带接口，方向枚举改为独立的六方向枚举 |
| model/BedrockPart.java、BedrockCube.java、BedrockCubeBox.java、BedrockCubePerFace.java | 保留层级变换、方块旋转与顶点顺序；改用 LegacyPoseStack 和 LegacyVertexConsumer，集合改为 JDK 类型，数学常量和接口改为 1.7.10 可用形式；骨骼输出异常时也恢复矩阵栈 |
| AbstractBedrockEntityModel.java、BedrockModelProvider.java、BedrockModelUtil.java | 改接 ModelBase、Entity 与 AxisAlignedBB；移除高版本 RenderType 和 Sodium 专用分支，保留普通几何路径；补上 ModelBase 渲染入口，并让 renderToBuffer 的颜色参数传入骨骼 |

新增矩阵栈、顶点接口和 Tessellator 适配位于 `com.tianluoqaq.tlmnh.client.render`，JOML 按官方 Shadow 配置重定位到本工程包下；矩阵法线以余子式计算，
单轴缩放为零时仍能保留平面法线，提交时再归一化

CPU 测试覆盖 205 份非 Gecko 内置几何，以及层级与独立方块旋转、镜像、逐面 UV、零厚度平面和法线；不能据此判断动画或 Angelica 下的实际画面已通过验收

## Gecko 几何与骨骼

以下 34 个原文件均来自同一固定 TLM 提交，位于 `com/github/tartaricacid/touhoulittlemaid/geckolib3/`，保留原包名和已有作者头，
同时保留 TLM 与 GeckoLib 的 MIT 原文

| 相对目录 | 原文件与适配 |
| --- | --- |
| geo/raw/pojo | Bone、Cube、ModelProperties、UvUnion、UvFaces、FaceUv、RawGeoModel、MinecraftGeometry、LocatorValue、LocatorClass、PolyMesh、PolysUnion、PolysEnum、TextureMesh、FormatVersion、Converter，共 16 个 Java 文件；保留数据结构，JsonAdapter 注解改为 Converter 显式注册，适应模板的 Gson 2.2.4 编译依赖，版本比较改用 Forge 接口 |
| geo/raw/tree | RawGeometryTree、RawBoneGroup；恢复上游 OpenHashMap 与逆向扫描挂树次序，使用 RC2 已提供的 fastutil；增加重复名称、缺失父节点和环检查，避免上游重复寻找父节点时无法结束 |
| geo/render 与 geo/render/built | GeoBuilder、IGeoBuilder、GeoMesh、GeoBone、GeoModel；保留 Gecko 坐标、旋转符号、镜像、膨胀和网格布局；Vec3 改接 1.7.10，集合和只读视图改为 JDK 类型 |
| geo/animated | AnimatedGeoBone、AnimatedGeoModel、ILocationModel；保留独立可变姿态、隐藏控制和手持定位骨骼，替换高版本集合及 Stream.toList，未引入枪械联动实现 |
| core/processor 与 core/snapshot | IBone、ILocationBone、PointData、BoneSnapshot、BoneTopLevelSnapshot；保留姿态快照、提交与初始状态，原作者头保留 |
| util | VectorUtils、RenderUtils；接入旧 Vec3 和 LegacyPoseStack，保留平移、旋转、缩放及定位骨骼的变换顺序 |
| geo/IGeoRenderer.java | 保留递归骨骼与逐面网格输出，改接 LegacyVertexConsumer；移除高版本渲染类型和 Sodium 分支，由 1.7.10 实体渲染器调用；零长度法线安全归零，异常时恢复矩阵栈 |

GeckoLib 许可来源为原项目的固定提交，保留原作者署名：
[固定提交的 LICENSE](https://github.com/bernie-g/geckolib/blob/4533673f89c2f1301ba0676ab7240ed20fb7f784/LICENSE)

Java 25 测试覆盖全部 26 份内置 Gecko 几何、19 个酒狐基础模型及其 33 个 Gecko 选择对应的几何输入，并检查独立姿态、快照恢复、可见性、定位骨骼和异常清理；
同样的几何测试还在 RC2 客户端使用的 Gson 2.10.1 上运行，不将更高版本 Gson 打包进模组

动画控制器、关键帧解析、采样和 Molang 绑定见后文；几何和骨骼测试通过不能作为动画、酒狐完整姿态或 Angelica 画面的验收

## 实体渲染适配

新增独立文件 BuiltinMaidModels、BuiltinMaidModel 和 MaidRenderer 均位于 `com.tianluoqaq.tlmnh.client.render`，不复制高版本实体渲染器；
参考固定 TLM 的 EntityMaidRenderer 与 GeckoEntityMaidRenderer、GeoReplacedEntityRenderer 核对模型、贴图、缩放和坐标，
继承 1.7.10 RenderLiving 接入 Forge 既有渲染路径

BuiltinMaidModel 将 Gecko 的脚部原点和 X/Y 方向转换为 ModelBase 坐标，保留原几何数据；共享解析定义，每只女仆持有独立可变骨骼，资源重载时失效，弱引用缓存不保活已卸载实体；
CPU 测试覆盖全部254个选择以及旋转方块的两种坐标一致性；Bedrock组合和Gecko核心播放见后文，画面待验收

## Bedrock 内置动画

以下原文件均来自固定 TLM 提交，保留 `com.github.tartaricacid.touhoulittlemaid` 包名和 MIT 来源头

| 相对上述包名的路径 | 移植与改动 |
| --- | --- |
| api/animation/IModelRenderer.java、client/animation/script/ModelRendererWrapper.java | 保留旋转、初始角、偏移、旋转中心与显隐接口，操作已移植的 BedrockPart |
| client/animation/script/GlWrapper.java | 改用 LegacyPoseStack；线程内嵌套绑定恢复外层矩阵，动画结束后清理绑定，不调用脚本引擎 |
| client/animation/inner/IAnimation.java、InnerAnimation.java | 动画输入改为本工程逐帧状态；IAnimation 改为单抽象方法，注册表只接女仆所需类别，排除椅子、玩家替身和节日内容 |
| client/animation/inner/MaidBaseAnimation.java、MaidExtraAnimation.java | 保留原有头、耳、头发、四肢、眨眼、坐骑、坐姿、裙摆、翅膀、尾巴与组合顺序；移除枪械联动、娱乐方块和背包物品显隐，修复反向眨眼只有一种拼写骨骼时的空引用 |
| client/animation/inner/MaidArmorAnimation.java | 只移植普通与反向护甲显隐；温度、天气及护甲值动画不在当前内置模型所需范围 |
| client/animation/inner/MaidTaskAnimation.java | 保留按任务 ID 控制模型骨骼显隐的条件；首版实际任务仍只有空闲、近战与远程，显隐代码不会注册额外工作 |
| client/animation/inner/EntityBaseAnimation.java、SpecialAnimation.java | 保留悬浮、时钟、旋转、维度、随机选择及两个特殊模型的条件；修复地狱显隐比较，空随机骨骼集合安全退出，随机索引覆盖 Long.MIN_VALUE；旧世界日时在输入侧规范到一天内 |

独立适配 BedrockPose、BuiltinBedrockAnimations 和 MaidAnimationState 位于本工程包名；BedrockPose 每帧恢复命名骨骼及旋转方块生成的子骨骼，并保存本体与附件共用的整体变换；
MaidAnimationState 读取实体同步状态，动画不操作服务端世界

BuiltinBedrockAnimations 中两个固定脚本的改写来自内置包 `assets/authors_and_credits/animation/arm.js` 与 `float.js`，原作者 TartaricAcid，
固定提交下完整原路径见资源来源清单；保留原公式，去掉上游已弃用的推车和载具分支；该适配文件按 CC BY-NC-SA 4.0 提供，原脚本资源保持原字节，不能用 Java 代码的许可覆盖这些改写

实体新增同步索引 28 保存短时动作位，MaidPose 位于本工程包名；动作由服务端设置且不持久化，生活和战斗行为尚未接入这些触发状态，硬编码游泳等动作层继续实现；本阶段仅接入 Bedrock 资源组合，
不代表 Gecko 动画或整套女仆行为完成

## Molang 表达式核心

`com/github/tartaricacid/touhoulittlemaid/molang` 下 36 个原文件来自同一固定 TLM 提交，保留原包名和已有 Unnamed Team 的 MIT 许可头；
原许可另存于 licenses/UnnamedTeam-Molang-MIT.txt；它们提供词法、表达式树、绑定接口、求值器和循环，不引入外部脚本引擎

适配去掉 JetBrains 空值注解依赖，结构体存储和另两个原文件 `geckolib3/core/molang/util/StringPool.java`、`PooledStringHashMap.java` 改用 JDK 集合，
保留字符串池编号、结构体复制、短路求值和上游 1024 次循环上限；修复作用域末尾分号后仍尝试读取表达式的问题，并允许空作用域，
相关语法对照 [Mojang 官方 Molang 文档](https://mojang.github.io/bedrock-samples/Molang.html)

`MolangEngineTest` 使用独立上下文检查优先级、条件分支、变量和结构体复制、同一表达式在不同实体输入间隔离、函数参数、循环和错误位置；数学、变量、帧查询、Gecko 文件解析与控制器见后文，
不能将表达式测试当作 Gecko 模型动画完成

## Molang 数学与变量绑定

以下48个原文件同样来自固定 TLM 提交，保留 `geckolib3` 下原包名和 MIT 来源头，不添加 fastutil 或外部脚本引擎依赖

| 相对 geckolib3/ 的路径 | 移植与改动 |
| --- | --- |
| core/molang/builtin/MathBinding.java 与 builtin/math/ 下27个函数 | 保留常量、别名、参数个数和普通范围的求值行为；Clamp 去掉高版本 Mth 依赖；sin/cos 按角度输入，反三角按固定上游返回弧度；HermitBlend 保留原 ceil/floor 公式 |
| core/molang/binding/ContextBinding.java 与 binding/variable/ 下3个文件 | 保留通用函数、常量和动态变量注册，以及 scoped、public 外部读取、temp 地址绑定；集合改用 JDK，record 改为 Java8 可用的普通不可变类；高版本实体和方块专用注册器未导入 |
| core/molang/storage/ 下4个文件 | 保留实体内的作用域变量、公有变量共享 holder 和可增长临时数组；模型初始化清空全部状态，增加 clearTemp 供单次表达式执行前清理 |
| core/molang/util/PooledStringHashSet.java | 使用 JDK Set 保存原字符串池 ID，不改变名称映射 |
| core/molang/value/ 下4个文件 | 保留常数、表达式序列、提前 return 和角度符号包装；错误日志接本模组入口 |
| core/molang/context/IContext.java、function/ContextFunction.java 与 variable/ 下3个通用类型 | 保留数据实体、独立随机源和三类存储接口；不引入高版本 capability、Minecraft 单例和客户端世界类型，游戏查询在后续帧桥接中适配 |
| util/Interpolations.java 与 MathHelper.java | 保留原插值、角度归一化和几何辅助公式 |

新增独立文件 `MolangAnimationContext` 与 `MolangRandom` 位于 `com.tianluoqaq.tlmnh.client.render.molang`；每个动画实例独立持有变量和随机源，
数据输入不持有游戏实体；表达式执行前清理 temp，scoped 值跨调用保留，模型初始化才清空；子上下文只有明确提供公有存储时才能读取对应 public 值，不意外读到父实体的公有值

整数随机函数的正常范围保持下界包含、上界排除；等端点返回该值，避免上游 nextInt(0)；宽度使用 long，避免跨整数范围溢出；die_roll 和 die_roll_integer 单次最多1024次，避免表达式占用渲染线程过久，
普通次数仍保留上游取整差异

`MolangBindingTest` 覆盖全部数学入口及别名、固定随机种子与多实例交错、反向和等端点范围、public 变量可见性、temp 清理、模型初始化、提前 return 和旋转符号

## Molang 帧查询与主绑定

新增移植9个原文件：`core/molang/MolangParser.java`、`binding/PrimaryBinding.java`、`builtin/QueryBinding.java`、
`builtin/query/` 下的 Position、PositionDelta、ItemNameAny、ItemMaxDurability、ItemRemainingDurability，
以及 `core/controller/AnimationControllerContext.java`，相对路径均位于 geckolib3 下，来自同一固定 TLM 提交并保留原包名与 MIT 来源头

PrimaryBinding 保留 math、query/q、variable/v、context/c、temp/t、loop 和 for_each，集合改用 JDK；解析包装在成功或失败后都释放临时变量地址；
运行时继续由各动画实例独立保存变量和控制器 anim_time

QueryBinding 将内置动画需要的17类查询及两个耐久度函数接到纯数据帧，不导入高版本实体能力、物品标签或其他模组专用查询；保留固定上游的头部轴名称、每秒速度单位、位置插值和 position_delta 的部分 tick 语义；
未实现的查询不注册占位结果

独立文件 `MolangQueryFrame`、`MolangQueryFunction` 和 `MaidMolangFrames` 位于本工程包名；取帧从 RC2 实际实体的主手、副手、护甲、生命、位置及水中状态读取，
动画寿命由调用方的时间轴传入；纯数据不保存实体或世界引用，避免渲染缓存保活已卸载女仆

物品槽名保留不区分大小写，省略命名空间时使用 minecraft，满足酒狐原资源的 `Mainhand` 与 `bow` 写法；RC2 旧注册 ID 允许大小写并保持原字节，不将 IC2 等旧物品误判为高版本非法 ID；
实际取帧不会因旧 ID 字符差异中断

`MolangQueryTest` 使用实际主绑定检查查询单位、头部轴、独立控制器时间、多实例与更新帧、世界边界的 double 插值、主副手隔离、旧物品 ID、耐久度、临时地址清理和参数错误；这些 CPU 测试不代表实体取帧、
完整动画或实际画面验收完成

## Gecko 关键帧与内置动画查询

以下18个原文件来自同一固定 TLM 提交，保留 geckolib3 原包名、已有 Gecko 作者头以及 TLM 和 GeckoLib 的 MIT 许可

| 相对 geckolib3/ 的路径 | 适配 |
| --- | --- |
| core/builder/Animation.java、ILoopType.java；file/AnimationFile.java | 保留动画元数据和循环类型，fastutil 改用 JDK，record 改为普通类 |
| core/keyframe/BoneAnimation.java、event/EventKeyFrame.java、bone/ 下8个文件 | 保留线性、Catmull–Rom、前后值、角度符号和过渡关键帧，集合改用 JDK |
| core/event/InstructionKeyFrameExecutor.java | 保留按时间执行、剩余指令和每循环重置 |
| core/util/MathUtil.java、util/AnimationUtils.java | 保留插值和时间单位，角度常量改用 Math；移除本阶段不用的高版本渲染器查找 |
| util/json/JsonAnimationUtils.java、JsonKeyFrameUtils.java | 保留动画与关键帧格式，兼容 Gson 2.2.4；所有表达式使用严格解析，不能用零吞掉加载错误；未写 animation_length 时按最后一段的起点加时长计算，修正上游只读取段起点导致时长过短的问题 |

新增独立文件 BuiltinGeckoAnimations 和 GeckoKeyframes 位于本工程包名；加载器按清单顺序合并动画、共享解析定义并在重载时失效，错误包含文件与片段名称；采样器定位区间并限制进度，处理零时长、
首帧延迟和前后值跳变；MolangValue 在每个独立程序求值前清理 temp，避免不同坐标表达式重用临时地址

另有10个原文件来自 `client/animation/gecko/molang/`：TLMBinding、YSMBinding、PhysicsManager、MoveInputVariable、SecondOrderFunction、
BoneParamFunction、BoneRotation、Vec3fStruct、IPhysics 与 SecondOrder，均保留上游包名与 MIT 来源，SecondOrder 的 MicroCraft 作者信息保留

这里的 ysm 是 TLM 内嵌动画查询命名空间，酒狐等原资源直接依赖它；只适配当前内置资源使用的头部、装备、饥饿、闭眼、移动方向、骨骼旋转和二阶物理，不加载外部 YSM 模组或模型；女仆背包物品不在首版范围，
因此 has_backpack 由实际无装备状态输入；RC2 的鞘翅查询识别胸甲槽的 etfuturum:elytra

独立 MolangModelResources 随模型实例保存骨骼和物理状态，子上下文共享该实例资源，不保留游戏实体；普通二阶模拟保留上游公式，零频率按直接跟随处理，重复或倒退时间不推进，非有限参数不污染状态，长时间未绘制时最多追算一秒

GeckoAnimationTest 严格解析56份文件、1645个片段与13824条表达式，结合41个Gecko选择的各自骨骼执行205242次向量采样，其中酒狐33个选择；另测角度符号、默认时长、Catmull–Rom、前后值、
临时变量、指令、重载和错误清理；MolangModelQueryTest检查原查询含义、动态骨骼、物理隔离、收敛与异常边界；这些是CPU检查，不能代替实际画面验收

内置女仆模型的原始资源已导入，逐文件原路径与SHA-256见src/main/resources/assets/tlmnh/builtin-assets-provenance.json，
原始说明与作者信息保留在各命名空间的maid_model.json和lang/*.json；资产许可见THIRD_PARTY_NOTICES.md，渲染画面仍待验收，时间轴声音继续实现

## Gecko 播放控制器

以下17个原文件来自同一固定 TLM 提交，保留原包名和 GeckoLib、TLM 的 MIT 来源头，fastutil 改为 JDK 集合，Pair 改用既有 Commons Lang 接口

| 相对 geckolib3/ 的路径 | 移植与改动 |
| --- | --- |
| core/AnimationState.java、PlayState.java；core/builder/AnimationBuilder.java、RawAnimation.java；core/manager/AnimationData.java | 保留控制器状态、片段队列与循环覆盖；拒绝非有限复位时长 |
| core/event/predicate/AnimationEvent.java；core/event/KeyframeEvent.java、SoundKeyframeEvent.java、ParticleKeyFrameEvent.java | 保留动画事件数据及监听器接口，不加载高版本实体类型；声音和粒子关键帧尚未播放 |
| core/keyframe/AnimationPoint.java、KeyFramePoint.java、TransitionPoint.java、AnimationPointQueue.java、BoneAnimationQueue.java | 保留控制器独立时间、关键帧与过渡采样及骨骼队列 |
| core/controller/AnimationController.java | 保留谓词、过渡和片段队列；按原版实际轨迹保留首次过渡处理，循环从实际边界帧重新计时；端点、末尾指令、保持模式和停止后重播的其余适配仍待更多原版状态对照 |
| core/processor/AnimationProcessor.java；core/util/RateLimiter.java | 注入实例私有变量存储，使用固定60Hz指令调度；保留主层覆盖与parallel旋转累加顺序；无动画通道从最后姿态连续复位，重复绘制同一时刻不改变复位结果；无效或倒退的调度时间不污染限流状态 |

已有 BoneTopLevelSnapshot 增加各通道最后动画姿态，使用 double 保存复位时刻；新增独立接口 GeckoAnimationTarget 位于本工程包名，控制器只读取动画定义与状态，不保留世界或实体

GeckoControllerTest的15项纯数据检查覆盖过渡、单次与保持模式、原版循环边界计时、首尾指令、顺序叠加、复位、实例及控制器时间隔离、片段排队、停止后重播和几何重载；这些检查不包括实际画面，不能据此认定酒狐动画可用

## Gecko 核心动作与实体播放

新增BuiltinGeckoPlayer和AnimationClock位于本工程包名；动作优先级与层顺序参考固定TLM的client/animation/gecko/AnimationRegister.java、
AnimationManager.java和client/entity/GeckoMaidEntity.java，物品条件参考同目录condition/ConditionalHold、ConditionalSwing、
ConditionalUse、InnerClassify和ConditionArmor，不引入外部内容模组的控制器

保留8个前置层、主层、双手持物、挥动、使用物品、乞食、乘客、8个并行层和4个护甲条件层；主层按死亡、睡眠、游泳、攀爬、乘坐、坐姿、水中站立、受击、跳跃、跑、走、空闲选择；物品先匹配注册ID，再匹配类型和使用动作；
护甲条件动画不等于外部装备已经渲染到女仆身上，装备渲染层仍待完成

BuiltinGeckoAnimations按固定GeckoModelLoader.registerMaidAnimations补齐默认女仆动画，仅填充缺少的片段，保留模型自带动作；不补入外部枪械、魔法或奏乐联动的默认动画；
默认文件中first_person_mod_hide与上游一致为false，不查询外部第一人称模组

实体只在取帧时读取游戏状态，播放器持有纯数据、骨骼、变量和物理；资源重载或模型切换会创建新实例；独立时钟保留小数tick，暂停冻结，恢复时重新对齐输入，重复或倒退输入不回拨；骨骼求值后按上游叠加头部看向，重复绘制不会积累角度；
SWIMMING为服务端动作位，行为触发尚未实现

BuiltinGeckoPlayerTest检查主层优先级、物品条件、层顺序、暂停、头部及重复挥动，并对41个Gecko选择执行10种状态、1230帧CPU检查，其中酒狐33个选择；AnimationClockTest覆盖实例隔离、
暂停恢复、倒退和无效时间；这些检查未创建游戏窗口，不代表Angelica画面、装备定位、动作过渡或完整玩法通过验收

## 原版动画对照工具

tools/animation-reference-1.20.1使用同一固定上游提交，参考构建仍是Minecraft1.20.1、Forge47.2.0与Java17；
新增独立探针和回放器位于com.tianluoqaq.tlmnh.reference下，不进入GTNH分发JAR

upstream-probe.patch仅应用于独立参考checkout：EntityMaidRenderer提供临时输入作用域；AnimatableEntity、
AnimationManager和GeoReplacedEntityRenderer提供显式时钟、选片与位移故障；GeckoMaidEntity在核心和头部阶段采样；BedrockModel采实际绘制参数；
普通实体与默认none故障保持原计算路径

资源来源另核对CommonDefaultPack、CustomPackLoader和GeckoModelLoader；原版内置包位于上游资源的嵌套tlm_custom_pack目录，运行时解包后读取；
Windows参考checkout的JSON换行可与固定Git字节不同，工具只允许可复算的LF/CRLF变体，移植版仍保留Git原字节

CPU回放使用实际移植类、独立模型与输入快照，Java25运行，遵守工程Java8输出目标；当前仅两个空手待机夹具，不能据此宣称全部模型、动作或GPU画面通过；操作与实验限制见工具DESIGN.md

原版受控轨迹纠正了先前仅凭单元检查作出的两项动画时序假设：零时长过渡首次处理仍为TRANSITIONING，循环不保留跨帧越界余量
恢复这两项上游规则后，汉服酒狐115骨骼与Bedrock酒狐126骨骼各30步逐值一致，Gecko适用控制器状态与时钟也一致
比较基线和数值容差未改；该结果仅覆盖已审查的空手待机合同，仍需更多状态与实际渲染检查

## 内置模型窗口与提交检查

新增模型选择窗口和目录筛选逻辑均位于com.tianluoqaq.tlmnh，使用现有固定254选择目录，不读取外部模型包
选择器通过原版C11发送固定目录索引；按实际1.7.10包的有符号byte解码后恢复8位索引，254返回、255拒绝
独立窗口避免与主界面动作共用编号，服务端每次复核主人、距离、世界实体身份、鼠标物品和目录白名单
模型名称从未改动的原模型包JSON语言读取，缺少翻译时显示模型ID，新增操作文本另有简中和英文语言条目

RenderSubmissionProbe在显式开发模式下采一次真实本体批次，比较同一姿态的预期流与Tessellator适配器实参
另读实际默认纹理单元绑定、draw调用与恢复状态，可显式注入UV偏移、错误绑定或丢顶点；数据不经保存后修改
预期流与实际流共用几何算法，故仅检查提交桥和绑定，不构成上游几何或完整GPU成像的独立证明
开发GUI入口复用真实按钮发送路径，只观察客户端副本，服务端接受仍须通过后续同步或服务端检查核对

## 命名、近战基础与箭物理实验

新增命名窗口、命名协议、规则和容器均位于com.tianluoqaq.tlmnh，服务端重新核对窗口、nonce、实体身份、主人、距离与命名牌；
EntityMaid阻止拒绝的命名交互继续进入原生ItemNameTag兜底，已写名牌保留主人直接命名路径
参考固定上游UseNameTagEvent、原版Player交互和1.7.10 EntityPlayer.interactWith、ItemNameTag的完整调用顺序

近战由同一MaidBrainCoordinator仲裁跟随与追击，新增目标筛选、受限控制器和原生攻击适配器；
参考固定上游IAttackTask、TaskAttack、MaidMeleeAttack及1.7.10 EntityMob.attackEntityAsMob的伤害、附魔和击退管线，成功命中才调用Item.hitEntity
此批是近战基础，没有实现完整生活竞争、弓箭发射或原版行为benchmark，不能据此宣称智能程度提升

新增common/combat纯数据箭轨迹与拦截求解器，按1.7.10 EntityArrow.onUpdate的实际顺序先移动、再乘阻力、最后扣重力；
空气0.99F、水中0.8F、重力0.05F保留原浮点常量的有效值，使用离散分段求交，尚未接入女仆远程行为
MaidArrowPhysicsProbe用于核对RC2运行时实际箭实体，固定介质自由飞行与点拦截不代表碰撞命中率或其他维度物理

## 非零头转向和真实输入绑定实验

原版head_turn合同保留同一上游版本，用实际实体字段驱动四段共72步转向，两模型各独立重建三次；
Gecko原版GeoReplacedEntityRenderer对相对yaw作wrap/clamp后将yaw、pitch取负，Bedrock常规render参数保留正号
移植版MaidMolangFrames按该路径转换，独立HeadBindingProbe检查真实RC2实体、原生render参数与播放器实际消费的query

BuiltinGeckoPlayer增加可选只读核心姿态观察点，默认路径不复制姿态；回放另以同输入的新播放器检查观察前后输出一致
Gecko分别比较核心、头部、最终层，Bedrock只报告实际可观测的最终姿态；不把不同阶段强制判为相等
故障从真正播放器消费输入前注入，不改写保存后的JSON；这些有界检查仍不能证明GPU像素和全部动作已经正确

## 弓箭、生活与死亡核心的新增适配

本批把离散预瞄接入实际原生弓箭行为，新增MaidBowAttack和ObservedTargetMotion，参考固定上游TaskBowAttack、MaidShootTargetTask及目标版本EntityArrow/ItemBow
按用户选择保留RC2箭的重力与阻力，使用连续可见位置拟合速度，丢失视野、换目标、时间不连续或瞬移后清理历史
首批只支持原生弓和箭，发射前检查已加载空气弹道和静态碰撞实体，生成成功并复核事件后的资源和箭状态才扣箭与耐久
拉弓、路径和姿态均按动作归属释放；未来横穿的友军、介质切换与其他武器尚不在此批保证范围，原版O/F/N测评仍未执行

common/life参考固定上游MaidStealEdibleMoveBlockTask、CakeEdible、MaidBedTask与InitEntities作息
实现柜上偷吃、背包摆蛋糕、床占用和醒来清理，由唯一MaidBrainCoordinator仲裁，临时外观采用原生方柜与床
当前要求在线非旁观主人在附近，离线生活、居家点与完整上游外观尚未完成，不宣称完整生活等价

common/lifecycle参考固定上游初始魂符、ItemFilm、BlockShrine和EntityTombstone，保留神龛生命代价及原主人
新增服务端世界凭证账本处理重复兑换和死亡库存，魂符/胶片物品仅携带凭证；十二槽库存与复活身份分开保存
普通保存与异常掉电的保证分开：WorldSavedData与玩家/区块并非跨文件原子事务，当前不能承诺任意断电都不丢不重

四份魂符与胶片资产保留原Git blob字节及CC BY-NC-SA 4.0许可，见assets/tlmnh/core-assets-provenance.json
源目录为固定上游textures/item，目标为1.7的textures/items；原650份内置资产清单不变，校验器额外核对这四份资产
新方块与墓碑当前外观仅供Indev链路测试，最终素材、GTNH配方与科技门槛继续待验和讨论

## 独立几何语义观测与真实重启夹具

新geometry v2合同通过实际骨骼发射边界记录bone/cube/face/corner和drawIndex，逐项核对位置、法线与UV
port新增可选GeometryBoneObserver，IGeoRenderer仅在consumer实现该接口时复制实际mesh面mask并通知开始/结束；普通绘制不创建观察数据
原版wrapper委托原IGeoRenderer方法，移植端仍调用生产BuiltinMaidModel.renderGeometry；两边分别新建plain/traced模型检查观察器中性
共享工具只记录身份和公开状态，不计算预期几何；绘制次序另行报告，几何数值相等不表示材质或共面覆盖已通过
此前严格顺序流第四轮整体失败保持原判，不能用只读重新对应的诊断补标为通过；新合同须重新采集和执行实际故障

MaidLifecycleRestartProbe仅在开发开关下建立隔离夹具，实际区块保存女仆、墓碑和神龛，世界账本保存凭证状态
校验要求不同JVM从磁盘载入，固定公式核对十二槽78物品而不从测试快照复制权威库存
这项检查不包含真实玩家player.dat和异常掉电的跨文件原子事务，结果须与自然行为和客户端网络交互分别记录

## Gecko 原版遍历顺序恢复

独立 v2 基准先保留并报告原有17组兄弟次序差异，随后恢复固定上游RawBoneGroup的OpenHashMap与RawGeometryTree逆向扫描挂树流程
在挂树前独立检查重名、缺父和环，不让安全校验的容器次序改变最终原版遍历；空树维持安全返回
使用RC2已提供的fastutil 8.5.18，开发配置为compileOnly、runtimeOnlyNonPublishable和testImplementation，不shade或覆盖整合包全局版本
上游固定参考环境使用8.5.9；两版本对26份固定几何、4729骨骼的独立容器实验全序列一致，仍须以生产渲染路径和实际RC2类来源验证集成结果
新汉服测试的根及17组兄弟字面期望来自真实原版capture，未由移植实现重算期望；实际材质与共面覆盖继续独立验收

## 自然行为开发探针

新增MaidBowRuntimeProbe与MaidLifeRuntimeProbe位于本工程包名，仅在显式开发命令下搭建有界测试输入
它们通过FML tick事件观察生产女仆的自然AI与世界更新，不另算预期行为，不手动推进实体、箭或控制器
内存PLAY主人、脚本移动靶和空气脚手架是隔离测试条件，不冒充真实玩家网络或原版智能测评
弓箭以本轮箭伤源和真实生命变化识别碰撞命中；控制器原getHits更名为getSuccessfulAttacks，明确该计数包括成功发射，不能作为命中数
探针正常结束与测试服停止时释放自己创建的实体、方块、观察器和连接；通过标志要求清理无误

自然偷吃实验发现1.7导航会在距邻格终点一个实体宽度内完成，停点仍可能在柜上蛋糕的2格交互球外
MaidLifeController只在本动作路径已结束且接近同一安全终点时，通过原生MoveHelper补完邻格接近；保持原交互半径、视线和碰撞，不以传送或延长超时通过

RC2的Hodgepodge 2.7.215会让NetworkDispatcher.get在自身属性为空时返回全局真实连接fallback，不能据其非空判定内存通道绑定了玩家
新增IsolatedPlayerChannel核自身channel身份、open状态、原始FML_DISPATCHER属性及仅含NetworkManager和Netty内存处理器的pipeline，独立记录fallback是否存在
正常FML PLAYER发送直接读取原始属性，null表示无目标；探针不调用全局fallback的发送或关闭方法，结束前再次检查自身属性
这项隔离结论限已核对的PLAYER路径与测试pipeline，不概括全部第三方路由；真实客户端网络另验
客户端开发状态另读原生GuiDisconnected的错误文本，供自动保存断开原因，不再仅报告界面类名

## 行为观测与策略生命周期

新增 `common/ai/telemetry` 与 `common/ai/policy` 为本工程独立实现，不复制外部行为框架
生产协调器仍使用旧固定优先原型，未完成的忠实与增强模式拒绝执行；记录开关默认关闭
路径记录区分查询提交、原生返回与适配器处理，成功发射在生成复核和资源提交后登记，不把发射计数当作命中
生活记录分别标记偷吃、摆蛋糕与入睡阶段效果，入睡不等于整个睡眠任务完成

开发用 `NativeCombatLedgerReader` 与 `MaidEpisodeRecorder` 只读目标版本 CombatTracker 和 CombatEntry，
结合伤害前、死亡回调与服务 tick 末采集；依据条目对象身份去重，保留原始金额与不可归属净变化
没有向原生伤害方法插入写操作，也没有把 LivingHurt 或攻击返回值当作最终伤害提交
该观测合同面向已核 RC2 原生路径，不承诺任意第三方重写伤害账本后的精确归属

原包 EntityMaid 另增加保存数据读取与死亡边界，读取前让旧策略退役，防止构造 UUID 与存档 UUID 交替期间复用旧 token
1.7.10 实体没有 onChunkUnload 接口，卸载及重新注册由独立 Forge 生命周期监听器处理
新增实现仍位于 `com.tianluoqaq.tlmnh`，上游 EntityMaid 的原包名与来源保持不变

## 忠实跟随的组件合同

原包 MaidFollowOwnerTask 只移植检查与启动逻辑，`tryStart` 表示检查通过并执行 start，不表示移动成功或完整原版 Behavior 生命周期
FOLLOW_CORE_V1 当前用于组件对照，生产策略继续使用旧原型，完整忠实 F 和增强 N 仍不可执行
tools/follow-reference 在独立 MC1.20.1/Forge47.2.0/Java17 中调用原版任务，主人引用和部分状态为明确的受控依赖
原版真实 Brain、游泳管理器与导航管理器保留；传送返回值桩不模拟记忆清除，原生传送子例另行实际调用上游方法
移植决策重放消费原版观察到的外部传送副作用，不将这种重放当作 RC2 原生碰撞已经还原

Rc2FollowTeleport 参考同一固定 EntityMaid.teleportToOwner，保留10次候选及每次7/3/7随机调用顺序
当前仅接受声明的干燥 STONE/air 场景，使用 RC2 原生碰撞及实体移动；未知输入返回 OUT_OF_SCOPE，不冒充原生不可达
MaidFollowTeleportProbe 用原版记录的整数流驱动实际 RC2 适配器，未登记组件实体、不推进自然 tick，不验证完整 Brain、在线主人查找或记忆层
导航归属在移动前后复核，资源被回调替换时保留替换路径并报告已发生的移动，不将部分执行算作成功

## 移动 sink 与原生阶段适配

新增 `FollowMoveActor`、`FollowMoveState` 和 `FollowCoreMoveDriver` 位于本工程包名，
根据固定 MC1.20.1 mapped Forge 输入中的 `MoveToTargetSink`、`Behavior`、`Vec3i` 实际字节码核对状态、方法次序和数值语义
它们不包含 Minecraft 原类，不打包原版或 Forge JAR，也没有把 RC2 的随机位置生成器当作现代 fallback
独立参考工具 `tools/move-reference` 使用实际原版方法采集受控结果，区别于世界自然 Brain 运行

`MaidBoundedPathPlanner` 将查询与安装分开，保留 RC2 原生 PathFinder 和导航执行；终点关系只描述观察到的三维几何事实
现代 partial-path、stuck、节点超时与 fallback 的平台对应仍需各自对照，不能由 `noPath` 推导

`MaidBrainCoordinator`、`MaidPolicyRuntime` 和 `MaidAfterNavigationGate` 为本工程独立接线，
根据两代原生调用顺序将开发组件放到导航之后，并复用既有 A1 token、身份和清理边界
原包 `EntityMaid` 新增 `updateAITick` 适配钩子，保留父类行为，不另注册第二套任务循环
完整忠实与增强模式仍未启用，新增源码及探针不等于相应自然功能已经验收

## LOOK 执行组件

`FollowCoreLookDriver` 是本工程的独立适配，依据固定 MC1.20.1 的 `LookAtTargetSink`、`Behavior` 实际字节码，
以及固定 TLM `MaidBrain` 的 priority0 注册方式核对生命周期；不包含或打包 Minecraft 原类
保留 45..90 tick 持续时间、世界整数随机调用、严格截止边界、运行时可见性检查及停止时只擦除 LOOK 的语义

`Rc2FollowCoreActor` 通过 RC2 原生视线控制器提交主人眼部位置，仍由原生 controls 阶段消费，不手动 tick helper
当前可见性来源为 `CURRENT_RC2_LOS`，没有宣称还原现代 sensor 的候选范围、刷新相位或缓存
停止不回写角度，也不撤回已提交的原生请求；两代 helper 的执行物理仍分别记录

`FollowCoreComponent` 在既有 after-native 入口按 look0、move2、follow3 执行开始与运行两 pass
新增 LOOK 会改变组合的随机消耗，`FOLLOW_MOVE_LOOK_COMPONENT_V2` 与旧任务、移动 sink 的独立证据分开
`tools/look-reference` 调用真实原版 LOOK 与选定三行为的 Brain 方法，受控记忆、时钟、导航和主人不冒充自然 Brain
原版采集、移植重放、RC2 原生执行和完整 F/N 的状态见 [开发进度](PROGRESS.md)

## 备用落点生成

`FollowFallbackSampler` 是本工程的独立数值与候选生成适配，依据固定 MC1.20.1 mapped 输入中
`DefaultRandomPos`、`RandomPos`、`GoalUtils`、`Mth`、`Animal` 和 `LevelReader` 的实际方法核对
保留角度查表、实体随机消耗顺序、十次尝试、限制区偏置、筛选短路及 float 光照评分
不打包 Minecraft 原类或整套源码，也不把 RC2 的 `RandomPositionGenerator` 当作现代实现
`fixtures/faithful/follow-fallback-utility-v1.json` 记录独立 Java 17 原版 utility 调用的输入、结果与类资源摘要，
已去除本机资源位置；不表示自然世界、变换后类定义或完整行为 AI 已验

RC2 接线保留原干燥域与路径归属限制，仅显式批准的扩展空气体积可使用 fallback
TLM 的 `hasRestriction` 随家园模式变化，因此家园关闭时不能仅因半径配置存在而额外消耗限制区随机数
备用路径创建使用同一有界原生查询与安装入口，不手动推进导航

## 原生导航停止反馈

`Rc2NavigationStopState`、`Rc2NavigationStopSource` 与 `MixinPathNavigate` 为本工程新增实现
依据 RC2 实际 `PathNavigate.pathFollow` 的 watchdog 分支记录清空前后事实，不分发或覆写整份原生导航类
保留原始超过 100 次更新、位移平方小于 2.25 的检测和原清空调用，反馈仅供认领该路径的有效动作使用
`FollowCoreMoveDriver` 的停止、冷却与重试流程未改，不将该反馈称为 MC1.20.1 导航物理等价
Mixin 配置和 refmap 使用固定 GTNHGradle/RFG 的生成与重混淆流程，双端实际验证状态见 [进度](PROGRESS.md)

## RC2 副手与盾牌

`common/combat/MaidItemUseState` 与 `common/combat/shield` 为独立适配代码，新增文件仍位于本工程根包
时间与使用条件参考固定 TLM 的 `MaidUseShieldTask`、`EntityMaid`、`TaskAttack` 及现代 `Behavior` 生命周期
主动近敌举盾、被动投射反应、严格超时边界和按 Item 的破盾冷却分别维护，不把盾牌当成阻止主手近战的全局占用
弓与盾使用同一份持续物品使用状态，旧使用者只能释放自己的授权，不能用副手盾的使用时间代替主手弓蓄力
这是明确的手归属安全适配，不声明现代物品使用的全部细节已经等价

目标平台通过 `com.github.GTNewHorizons:Battlegear2-for-Backhand:1.6.8-backhand:dev` 的公开 API 查询盾牌能力
该依赖仅用于编译，不传递解析其旧版依赖，不打包、重定位或复制其实现类；运行时复用 GTNH 已安装版本
服务端监听真实 `LivingHurtEvent`，从实际盾牌查询可挡伤害、角度和耐久减免，并调用原生 `ItemStack.damageItem`
不向玩家专用盾牌事件传入女仆，不伪造玩家，不采用 Battlegear 客户端体力值作为女仆权威状态
盾牌耗损使用 RC2 规则，TLM 现代的伤害阈值与耗损公式没有沿用

TinkersDefense 圆盾和鸢形盾继续使用真实物品回调及 `InfiTool` 状态；坏盾拒绝格挡是显式安全约束
破盾按原生斧或精确的 Forge `axe` 工具能力映射，能力查询在副本上执行，不按显示名称推断，也不自动把 GT 链锯当作斧
普通任务取消释放短时使用状态，不能清除同一 Item 的破盾冷却
被动盾是实体自身的伤害反应，不启动不可用的 F/N 策略，也不为行为测评取得资格

开发夹具通过实际注册表及 TConstruct `ToolBuilder` 构造七种合法盾，保留工厂事件和完整物品快照
直接调用真实伤害管线的组件结果与自然举盾、真实箭碰撞、联机及装备渲染分别验收
本切片尚未移植玩家专用箭捕获路径，不将直接伤害取消声明为插箭转移已经完成
动画输入新增盾牌分类，实际手部装备层按下节独立实现与验证

## 双手物品渲染与坐标桥接

新增 `MaidHandAttachment`、`MaidHeldItemTransforms` 和 `MaidHeldItemLayer` 位于本工程包名
附件规则依据固定 TLM 的 `LayerMaidHeldItem`、`GeckoLayerMaidHeld`、`BedrockModel` 和 `RenderUtils`
保留 Bedrock 选定部件及定位骨骼语义、Gecko 根到手部 Locator 链，不为读取附件再次推进动画或乘实体缩放

现代附件与 RC2 原生物品坐标通过显式矩阵桥接；普通物品依据当前 Forge 的 `RenderBiped`/`ItemRenderer` 入口，
七盾的独立左手坐标参考 RC2 Battlegear2-for-Backhand 1.6.8 的第三人称盾牌入口
复用实际注册的 `EQUIPPED` 物品渲染器及其多 pass，不复制玩家事件、Backhand 库存或第三方渲染器实现
这套桥接是移植物品的坐标合同，不能仅凭矩阵测试声明两代物品外观完全相同

新增作用域保护原生矩阵、图集过滤状态和已核 Angelica 软件状态；对未知渲染器的私有状态不作通用恢复保证
开发用 `HeldItemGpuProbe` 观察真实本体后的装备绘制，分开记录原生调用、GPU 查询、像素、深度和清理
没有修改第三方物品渲染器注册，也不把调用计数或单张截图当成完整画面验收
源码许可与资源许可继续分别记录；引用 BG/TD 的部分须随最终分发许可一起复核
原版运行时附件对照、全部酒狐、不可见实体、自然世界及光影仍需后续验收

开发探针另支持四个预定视角，各视角独立准备真实本体；同一事务内再用未画本体的空颜色/深度基线诊断物品
诊断保持原深度、透明与裁面状态，不能据单独物品可见判定合成画面正确，也不声称不同视角使用同一姿态
探针只读记录手部、骨链和 Angelica 的实际批次状态；活动批次、待处理几何或状态未知时拒绝该观察合同，
不会为取得像素而刷新世界全局队列或改变捕获模式

`PackagedClassResourceResolver` 为本工程的纯 JDK 开发工具，按实际资源 URL 与 CodeSource 验证同一 JAR，
通过反射读取物理条目并逐字节比较；多版本路径须符合清单与实际 Java 版本，不推测最高目录，也不把资源字节称为变换后类定义
身份输出保留原摘要表，另记录逻辑条目、物理条目及多版本编号

`MaidShieldRuntimeProbe` 使用真实登记女仆、原生伤害输入与世界 tick，独立记录被动维护、主动时长和按 Item 冷却
命令仅排队，世界修改由服务 tick 执行；清理等待原生引用移除，合成主人的内存连接与真实玩家连接分开
弓、生活和盾牌开发夹具共享预约锁；这项接线不表示旧夹具的全部线程或失败清理行为已经重新验收
该工具不手工推进女仆 AI，不把直接弹射伤害输入称为真实箭飞行或完整行为测评

## 原版自然跟随采集工具

`tools/follow-natural-reference` 是独立开发工具，只在 MC 1.20.1、Forge 47.2.0、Java 17 的固定原版 TLM 环境运行
它观察真实登录主人与真实登记的原版女仆，保留原活动、行为竞争和世界自然 tick，不手工调用 Brain 或替换女仆 AI
重复运行差异和实际移动属性先用于方法核验；采集工具有效不表示忠实 F、增强 N 或完整 O/F/N 测评通过

## 家园与日程活动点

家园设计参考固定上游 `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6` 的 `SchedulePos`、`InitEntities` 日程注册、
`MaidConfig` 范围及 `MaidMoveToBlockTask` 的主人/家园判断
独立实现 `MaidHomeSettings`、`MaidHomeArea` 和 `MaidHomeController`，使用 RC2 原生 DataWatcher、NBT 与导航；原包 `EntityMaid` 只接同步槽及持久化入口
默认工作/休闲/休息半径为 12/6/6，保持上游日班与夜班时间分段；活动点以女仆方块坐标设置，范围判断采用严格方块距离球
当前返家使用原生步行及同维度安全落点，异维度处理和完整随机生活仍待实现；未知存档版本保持原数据并禁用家园，避免覆盖

安全远距返家另参考同一固定上游 `SchedulePos.tick` 与 `TeleportHelper.teleportToRestrictCenter`
`MaidHomeTeleport` 保留家园候选 X/Z ±3、Y 向上 0～3 及最多 10 次尝试，使用 RC2 原生碰撞、液体和实体检查
仅采用已加载且仍在当前家园范围的落点，排除已知火焰/仙人掌并清理本动作；不是完整现代 randomTeleport 等价实现

## 原版女仆主界面

主页面与空背包页面、容器按固定上游 `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6` 移植，保留原包名
来源为 `client/gui/entity/maid/AbstractMaidContainerGui.java`、`backpack/EmptyBackpackContainerScreen.java`
及 `inventory/container/AbstractMaidContainer.java`、`MaidMainContainer.java`、`backpack/EmptyBackpackContainer.java`
适配 Forge 1.7.10 GUI/容器 API，原版玩家槽位在前；默认背包最后一格只建立一个逻辑槽，避免重复映射
服务端动作复用既有权限与同步，独立图集按钮、预览恢复和物品转移适配保留工程包名

七份原版 GUI 与槽位素材按原始字节导入，语言键从上游 JSON 选取并保留原文，来源与摘要见
`src/main/resources/assets/tlmnh/gui-assets-provenance.json`，资产沿用 CC BY-NC-SA 4.0，代码沿用 MIT
当前为开发开关下的原版主页面实验入口；任务/女仆设置、声音选择、原版模型列表和完整提示仍待移植
现有自制页面保留作开发隔离工具，不作为正式界面或原版视觉等价验收

`GuiReferenceProbe` 扩展既有 MC 1.20.1 参考工具，只在真实虚空场景打开原版菜单并在实际渲染帧末截图
它使用原版 `EntityMaid.openMaidGui`，不替换界面、槽位或预览实现；记录真实分辨率、缩放、语言、指针、姿态、槽位和运行定义身份
原版截图须与移植版使用相同输入条件；动画、气泡、物品与世界内容单列，不能用静态背景相同推定全部表现等价

## 原版模型选择主页面

`AbstractModelGui`、`MaidModelGui` 和 `SkinGuiNumber` 保留固定上游包名，来源为同名 `client/gui/entity/model` 文件，代码沿用 MIT
适配 1.7.10 普通 `GuiScreen`、原图集、6 个内置包及 11×5 网格、滚动搜索和实时预览，按 `DefaultPackConstant.MAID_SORT` 的固定内置顺序显示
`BuiltinMaidPacks` 将原包元数据对应到既有全局白名单索引，模型消息仍经服务端容器确认；原型与原版页面的返回目标独立保存
原生 FML `OpenGuiHandler` 在页面初始化后设置容器窗口号，普通页面显式安装同一容器，避免引入另一套选择协议或库存页布局
预览实体仅在客户端创建且不登记入世界，最多保留 64 个，关闭页面后清理；73 按原版关闭图标缓存的条件验证实时预览模式
Shift 详情、关联音包及完整输入矩阵仍待补齐，本段不表示整套模型界面已验收

新增模型页图集按原字节导入，摘要继续记入 `assets/tlmnh/gui-assets-provenance.json`，语言保留上游原文
原版参考工具新增真实模型页、标签点击、搜索响应器与可见模型 ID 记录，所有新截图按其实际工具与运行身份单独绑定

## 原版模型图标缓存

`CacheIconManager`、`CacheScreen`、`CacheIconTexture` 沿用固定上游同名文件的包名与 MIT 许可
保留默认关闭、再次进入生成、每帧 5 张、左上 256×256 截图和纯绿抠除，以及 24×24 显示和实时预览回退
`MaidIconCapture` 独立适配 1.7.10 截图格式与状态恢复，资源重载时删除本模块持有的纹理并重置队列；纹理上传后释放 CPU 图像
`MaidClientPreferences` 保存本机开关，未增加服务端配置或网络消息；缓存页使用同一权威容器，原型模型页不走缓存
`MaidCacheCheckbox` 按原生 1.20 Checkbox 的尺寸、文字位置和勾选形状适配，背景复用游戏自带的 1.7.10 控件图集，不分发 Minecraft PNG
本段描述来源与实现范围，实际通过范围以进度文档为准；Shift 详情、关联音包和其他设置页仍待补齐

## 原版模型详情页

`AbstractModelDetailsGui`、`MaidModelDetailsGui`、`ModelDetailsButton` 和 `DebugFloorModel` 来源为固定上游同名文件，保留原包名和 MIT 许可
移植顶部按钮、十个状态开关、模型名和比例栏、旋转平移缩放及原始数值边界，详情页沿用当前模型容器，不提交预览状态或装备
`MaidPreviewEntity`、`MaidPreviewSeat`、`MaidDetailsPreview` 独立适配本机预览姿态、RC2 副手盾牌和旧版矩阵/光照；低凳只作未注册预览，不加入家具物品
四个护甲开关保留上游金头盔测试物品；独立预览使用原版详情固定的全亮输入，普通女仆的服务端姿态权限保持原有规则
详情图集、地板与低凳模型/贴图沿用原字节和资产许可，来源及摘要记入 GUI 资产清单；语言继续保留上游原文
此段记录实现与来源，实际渲染、装备和完整界面验收范围以进度文档为准

## 原版声音包选择与本机试听

固定来源仍为 TLM `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6`，原包文件保留来源头与 MIT 许可

| 上游文件 | 移植与适配 |
| --- | --- |
| client/gui/sound/MaidSoundPackGui.java | 保留 400×220 双栏、每页 4 包/13 类、选择/应用/试听、作者链接确认；应用经服务端容器复核，不由客户端写真实音包 |
| client/gui/widget/button/FlatColorButton.java、SoundPackButton.java、SoundElementButton.java | 保留原色值、尺寸、文字与图标位置，改接旧版按钮事件和原生字体；首版仅内置静态图标 |
| entity/passive/DefaultMaidSoundPack.java | 保留两包 ID 与 75% PECO 的初始化概率 |
| client/sound/data/MaidSoundInstance.java | 保留跟随实体及死亡停止，改接 1.7.10 MovingSound 和生成的原生声音事件；同时用于本机试听及世界消息，声音触发和验证范围见后文 |
| EntityMaid.java 的 SoundPackId 状态 | 新增 DataWatcher 30、原 NBT 键及旧档缺键迁移；仅接受两内置包，旧档缺键保留初始化值 |

`BuiltinSoundCatalog`、`ContainerMaidSounds` 和导入脚本为本工程新增适配，位于 `com.tianluoqaq.tlmnh` 或 scripts
目录从固定 Git blob 生成，保留 CustomSoundLoader 的事件顺序、数字文件名规则和追加复用关系，不将复用误写为仅缺失时回退
原版模型数据没有指定关联音包，不能据模型名称虚构酒狐专属音包；世界语音与两级频率基础见后文；模型关联入口、原版设置控件和时间轴音效仍待完成

配音文件按独立作者条款处理，不能由 TLM 代码 MIT 或模型 CC 许可推断声音再分发权
源码仓库只含目录、映射、原说明/图标及摘要，435 个配音文件只生成到被忽略的本机构建输入；详见 THIRD_PARTY_NOTICES.md 与 DEVELOPMENT.md
这不是正式版声音功能或配音分发许可的验收结论

声音页保持原版普通 Screen 基类，并保存权威容器引用；不继承物品 GuiContainer，避免把声音页当作 NEI 物品窗口
应用后重建本机按钮、清除旧焦点；已用图标仍等待服务端音包状态同步，未改为客户端直接应用

## 世界语音、客户端过滤与声音配置基础

固定来源为 TLM `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6`，以下原文件保留原包名与 MIT 来源头

| 上游文件 | 保留与适配 |
| --- | --- |
| network/message/PlayMaidSoundMessage.java | 保留服务端附近发送、客户端按实体播放；改为两内置包/31 事件索引的 26 字节消息，附带维度及 UUID，网络线程只入队 |
| client/event/MaidSoundFreqEvent.java | 保留女仆频率乘本机全局百分比、随机大于阈值时拒绝，满频率及 GUI 试听不抽随机值；只过滤 MaidSoundInstance |
| entity/passive/MaidConfigManager.java | 本轮仅接声音频率及原 MaidSubConfig.SoundFreq 键，保留未接入的其他子配置 NBT；使用版本化字符串元数据，不冒称其他设置已生效 |
| util/SoundUtil.java | 保留早晨/傍晚/雨/雪/冷/热顺序、每分支先抽随机值及原始 dayTime，不擅自改为每日取模；生物群系判断接目标温度/降水 API |
| api/event/MaidPlaySoundEvent.java | 改用旧 Forge 可取消事件，仍在服务端选择环境/受伤/死亡及拾取声音时触发，不引入静音饰品或其他物品 |
| EntityMaid.java 的世界声音方法 | 接任务环境音、驯服、受伤、死亡和原玩家受伤 120 tick 冷却；旧伤害入口用可恢复上下文提供 DamageSource，避免客户端受伤通知重复播放自定义语音 |
| client/sound/data/MaidSoundInstance.java | 补原试听标记、静音检查、移动跟随及实体/世界失效停止；GUI 试听明确传 true |

新增 `MaidSoundNetwork`、`MaidWorldSoundState`、`MaidSoundFrequency`、`MaidWorldSoundPlayer` 及开发入口均在 `com.tianluoqaq.tlmnh`
配置元数据使用 31 号字符串，避开 float 类型 31 与旧元数据 `0x7f` 结束字节的冲突；原家园 29 和音包 30 保持
1.7.10 FML 生成流程未提供通用生物 UUID 同步，因此通过 IEntityAdditionalSpawnData 补 16 字节 UUID，再校验世界声音身份；没有用每端随机 UUID 比较来误拒绝全部消息
`Silent` 按原实体 NBT 名称保存，并用现有标志位同步；普通非女仆语音继续走原生声音路径，静音语义保持

全局频率保存在本机客户端配置，默认 100%，每只女仆默认 1；本轮先接后台状态与过滤，原版设置页面及全局控件仍待移植
拾取声音计数 API 已保留，实际地面物品拾取流程和动画时间轴声音尚待接入；不能把这个入口写成完整拾取功能
原音频仍只在被忽略的本机构建输入，分发条件未解决；世界语音的局部验证不能替代全部配音、自然玩法和正式版跨模块验收

## 原版女仆设置页

`MaidConfigContainerGui`、`MaidConfigButton`、`MaidConfigContainer` 来自固定上游同名文件，保留原包名与 MIT 来源
页面保留原设置图集、八行顺序、164×13 控件、左右箭头命中范围和文字位置；玩家背包沿用原 36 槽及主背包/快捷栏转移
本轮接通声音频率，左箭头减 0.1、右箭头加 0.1 并限制到 0～1，服务端按当前值执行，客户端等待同步确认；使用固定 C11 动作号 24/25，不传任意浮点或整份未实现配置
其余设置行在 Indev 标注待移植并禁用，后续与对应渲染、拾取和通行功能一同接入；不是完整设置页验收，也不以空开关冒充功能
原图集字节、来源与摘要登记在 GUI 资产清单，原语言文本保持；开发版待移植提示使用本工程语言文件
参考探针新增原生设置标签及箭头点击和频率观察，只调用上游实际界面，没有替换原设置 GUI

## 字体对照边界

现有开发探针增加实际字体模式、固定文本宽度及资源摘要观察；原版参考探针支持两个固定字体输入，并沿用原资源重载流程
字体诊断包只在本机参考环境保存，Minecraft 和整合包字体文件均未加入源码仓库；实际界面继续使用当前 GTNH 字体和资源包
同字体的选定数值字形位置一致，原始颜色差异仍记录；原版文字着色器采样全亮光照贴图，不能仅凭不同环境的截图像素数推断移植有误
用户已明确字体接近即可，后续以原版布局、控件和功能为重点，关联功能批量实现后统一实机核对

## 拾取与通行设置批次

固定上游仍为 `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6`
`entity/passive/PickType.java` 和 `api/event/MaidPickupEvent.java` 保留原包名，后者改为旧 Forge 事件类型，排除首版不移植的 PowerPoint 实体
`MaidConfigManager` 扩展拾取类型及 OpenDoor/OpenFenceGate/ActiveClimbing 原 NBT 键，继续保留未知子配置和旧声音元数据；元数据升级为一个带版本字符串，不增加实体槽位
`MaidConfigContainerGui`、容器和主页面接入服务端相对动作 26～30，拾取总开关与偏好类型分开保存；前三项视觉设置继续禁用，不用无效果开关冒充移植

新增独立文件 `MaidPickupService`、`MaidTraversalController`、`MaidPathQuery` 位于 `com.tianluoqaq.tlmnh`
拾取服务参考原实体的 pickupItem/pickupXPOrb/pickupArrow：支持模拟插入、取消事件、余量、拾取通知与原五次计数语音，按原版可用库存顺序填背包和双手，不自动穿甲
经验修补读取 RC2 实际注册的 `enchantment.mending`，沿用 TLM 从双手和护甲随机选择一件装备、每点经验修复两点耐久的规则；未引入饰品或其他内容联动
普通箭回收检查实际落地 NBT、拾取权限和摇晃时间，创造专用箭及未知自定义箭不转成普通弹药

通行控制参考上游 `MaidInteractWithDoor` 与 `MaidClimbTask`，沿已安装路径处理，不创建第二套移动目标
闭合栅栏门和木门在女仆的局部原生寻路视图中按配置放行，世界碰撞保持真实；沿途开门，通过后关闭，避让仍在通行的实体和红石供电，不打开铁门
爬梯接入已有梯柱的局部竖直路径和原生移动，保留目标版本自身的运动限制；远处找梯、复杂跨层路线和主动寻找物品仍待后续导航实现，不表示完整 F/N 或原版 AI 对照已通过

RC2 实际原版木门为 MalisisDoors `1.19.11-GTNH` 的替换类，源码固定 `b0d85584bdd457da0d58fcd565b64b6086627f97`
已核 `door/block/Door.java` 的原生村民开门入口和 `door/tileentity/DoorTileEntity.java` 的 OPENING/OPENED/CLOSING 状态及延迟元数据更新
新增 `Rc2DoorState` 只读取现有动画状态，仍调用 BlockDoor 的开门入口，保留动画、密码和红石规则；未增加 Malisis 内容玩法或硬依赖
同步夹具以真实开门请求及给定动画完成态检查后续关门，不把它写成完整自然动画或多门集成验收

## 主动寻找物品

独立适配 `MaidPickupController` 参考固定上游 `MaidPickupEntitiesSensor`、`MaidPickupEntitiesTask` 和 `MaidBrain.registerCoreGoals`
保留 30 tick 感知、可见/非水中/可放入筛选、先确认可达性及跟随优先；目标版本没有直接复用高版本 Brain API
移动接入现有 `MaidActionRunner`，新 PICKUP 身份参与原动作交接；坐下、抱起、拴绳、休息、危险和任务失效均退出，清理只处理自己持有的路径
当前实现限制每次处理 64 个候选、查询最近 3 个候选，并记录截断，不能把这项有界原型写成完整忠实 F、增强 N 或 benchmark 达标

## 界面信息、日程与药水效果

`AbstractMaidContainerGui` 补上原 owner/model_name/experience/favorability 提示的顺序和格式，日程内容参考固定上游 `ScheduleButton.getTooltips`，不添加首版排除的五子棋行
药水列表保留左侧位置、分类颜色、等级和时长格式，任务列表打开时隐藏，额外绘图在 tooltip 之前完成；没有改字体或全局资源包
`network/message/SendEffectMessage.java` 保留上游原包名、EffectData 原四个字段及 MIT 来源，另传目标版本药水 ID，避免把可重复的翻译键当成类型标识；改用旧 FML 编码，限制条目与键长，附维度、UUID、实体号和窗口号
旧版原生追踪不能持续向这个界面提供完整药水列表，因此改由已打开的女仆容器每 20 tick 向仍合法查看它的主人推送，不接收任意实体查询
新增 `MaidEffectNetwork` 和 `MaidEffectDisplay` 位于本工程包，网络线程只入队，客户端核世界/实体/UUID/窗口后更新 GUI 副本，不写原生药水或实体属性
1.7.10 的 getIsPotionDurationMax/isBadEffect 是客户端专属，公共快照只取实际整数时长，好坏颜色在客户端根据同一整合包药水注册表解析，未知类型保留中性色
新增活动/日程文本从固定上游语言文件提取，保留已有语言文件字节与换行，只追加缺少的原文并更新派生摘要

`MaidModelGui.notifyModelChange` 的 use_sound_pack_id 行为在现有模型容器交易中补齐：只有声明非空且已安装的声音包才应用，未知声音包不改当前选择
核对当前固定目录的全部 254 个内置选择，source_info 均未声明此字段；这些内置模型切换时保留当前配音符合原版，不把尚无声明的数据写成酒狐的自动配音功能

## 背部显示与原版页面默认入口

`client/renderer/entity/layer/LayerMaidBackItem.java` 保留原包名，合并固定上游 Bedrock 与 Gecko 两个背部物品层的核心条件与定位
`MaidBackItemRenderer` 位于本工程包，读取本体已完成姿态和 Gecko BackpackLocator；保留 EmptyBackpack 的偏移、模型 show_backpack 与睡眠/隐身隐藏条件，不引入枪械、拔刀剑或其他联动
目标版本以有最大耐久的物品对应基础可背挂物品；普通图标按 1.20.1 item/generated 的 FIXED 姿态桥接 RC2 ItemRenderer，已有自定义渲染器走 RC2 EQUIPPED 入口，外观待集中实机检查，不宣称其跨版本模型完全一致
第六背包格沿用上游 MaidBackpackHandler.BACKPACK_ITEM_SLOT=5，使用已核空闲的 DataWatcher 18 号 ItemStack 同步给实体追踪玩家；变化比较使用副本，原地耐久变化和清空也会更新
MaidConfigManager 沿用 BackpackShow/BackItemShow/ChatBubbleShow 三个 NBT 键，元数据升级到版本 3，继续读取版本 1/2；本批只开放已接实际渲染的背部物品开关，其余两项仍待接入
默认主人右键改为原版主页面；模型、配音和设置入口解除开发开关限制，保留归属/距离/状态/光标检查，旧自制页面和模型列表只在开发开关下开放
本批先合并编译与静态检查，实机与下一组关联视觉功能一起验证；不把待验渲染写成通过

## 普通聊天气泡

移植固定上游 entity/chatbubble 的集合、管理器、RandomEmoji、文字/图片数据及 datapack/KaomojiData，保留原包名与 MIT 来源
仅保留普通随机表情和受伤颜文字，未接入 LLM、等待、进度或外部图片协议；内置颜文字数据与 20 张 PNG、type2 背景保持原字节和 CC-BY-NC-SA-4.0 来源
默认每 1200 tick 按 UUID 错开检查，图片/颜文字等权，持续 300 tick，按服务器毫秒时钟每 5 tick 清理；集合上限 5，满时按原到期顺序和优先级替换
1.7.10 没有 LivingDamageEvent，受伤颜文字接在原生 damageEntity 返回后，仅在实际生命降低且当前没有气泡时添加，不把完全吸收或取消的伤害算作扣血

目标版使用已核空闲的 DataWatcher 19 号字符串同步显示副本，新增 MaidBubbleCodec 位于本工程包，只携带到期键、文字/图片类型和有界文字
最多 5 条、单条 256 字符，总长有界；客户端只解码显示，期限与优先级由服务端决定，气泡不写入存档
ChatBubbleRenderer、EntityGraphics、文字和图片渲染器保留原包名，适配旧版 Tessellator、字体和 Billboard；保留两侧/居中排列、九切片背景、尾部、原间距与文字宽度限制
EmojiReloadListener 读取已登记的 20 张内置 PNG，资源包可替换这些位置；不增加外部 GIF/图片导入功能，缓存只随短期气泡保留
客户端总显示偏好和每只女仆的原版显示开关均已接入；扩容背包不在首版范围，原版背包显示行保留无背包状态与范围说明
与上一批背部物品和默认界面合并做局部双端验证，不建立新的逐像素/全模型测试框架；正式版仍须单独完成跨模块集成

## 苦力怕逃离与被动回血

独立 MaidPanicController 参考固定上游 MaidHostilesSensor、MaidPanicTask、MaidRunAwayTask、SetWalkTargetAwayFrom 与 MaidBrain.registerPanicGoals
保留空闲任务允许、攻击任务禁用、可见且严格 8 格内最近苦力怕、速度 0.7 和最多 10 次逃离候选；坐下时可站起、结束睡眠并离开骑乘，危险动作先于普通生活和跟随
目标版读取完整局部苦力怕列表，每 20 tick 感知，使用已核的原生 RandomPositionGenerator.findRandomTargetBlockAwayFrom 与现有有界路径适配；
这部分尚未实现原版全部 HURT_BY 慌乱记忆或现代水下导航
候选搜索前确认涉及区块已加载，遵守现有家园边界；选择实际路径终点朝离开威胁的方向，保留已有同向路径，失败冷却 20 tick，不把未加载或暂缺路径当作允许继续偷吃
新增 PANIC 动作身份参与现有所有权交接；取消先撤销身份，只清理自己仍持有的路径，任务改变、暂停、卸载与威胁移除可退出

独立 MaidSelfCare 适配 EntityMaid.randomRestoreHealth：服务端低血时每 tick 以 0.0025 概率恢复 1 生命，满血不消耗这次随机值，不复活死亡女仆
上游 spawnRestoreHealthParticle 保留在原 EntityMaid 包中，使用旧版 mobSpell 对应 ENTITY_EFFECT、原颜色与分布；旧实体状态 100～102 携带 7～9 粒子的恢复提示，不改变原生驯服/爱心状态
普通背包进食和完整自保仍继续开发；不把这两项原型改进写成完整忠实 F、增强 N 或原版 benchmark 通过

## 核心自动进食适配

MaidMealController 的检查间隔、回血条件、营养计算与日常进食好感概率参考固定 TLM 提交
`8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6` 的 MaidHealSelfTask、MaidWorkMealTask、
DefaultMaidHealSelfMeal、DefaultMaidWorkMeal 和 Type.WORK_MEAL，原代码 MIT 许可见既有条目
临时换手改用背包原槽交换，保存时先取消并还原，避免隐藏物品遗漏或菜单改动被覆盖
不移植野餐垫、饰品和外部进食联动，也不另加玩家式饥饿消耗

RC2 食物能力按 AppleCore `3.3.13`（`eabf8deb429202b45731f444784a69553917af7b`）的
AppleCoreAPI.accessor.getFoodValues 读取通用营养值，不调用玩家专用 GetPlayerFoodValues
GT5-Unofficial `5.09.54.205`（`a3e1e11241a814c9fa0dd0973d5699548428f689`）的
MetaGeneratedItem.mFoodStats 与 GTFoodStat 用于核对 RC2 食物数据布局
本模组自行读取常规 GTFoodStat 的容器、药效和牛奶标记，不复制 GT 源文件，也不调用要求 EntityPlayer 的 onEaten
爆炸食物、未知 IFoodStat、自定义 ItemFood 进食回调暂不自动食用；不能据此宣称兼容所有整合包食物

## 好感、睡眠照料与雷击

从固定 TLM 提交移植 entity/favorability/FavorabilityManager.java、Type.java 与
api/event/MaidFavorabilityLevelChangeEvent.java，保留原包名和 MIT 署名
按原版 64/192/384 分级，基础生命为 20/30/40/80，基础攻击为 2/3/4/6
进食、睡眠、偷吃及死亡共用原版类别和独立冷却，计数使用原版 FavorabilityManagerCounter 存档键
旧 Indev 的日常进食剩余冷却和偷吃时间戳迁入该计数；未知类别保留
等级变化沿用 Forge 事件；未带入饰品、非首版活动或高版本进度触发器

EntityMaid.startSleeping 与 thunderHit 的适配分别接到实际女仆床入睡和 1.7.10 雷击回调
入睡先恢复当前生命上限，再发放最多每日一次的 2 点好感；升级当次增加的上限不会额外补满，沿用原版顺序
雷击只增加一次 20 点基础生命上限，不直接治疗
死亡确认后最多扣 2 点好感、不跨越已取得等级；在死亡物品及魂符身份保存前写入，取消死亡不扣分
本批未把好感的攻击距离、横扫参数接入完整战斗 AI，后续随战斗移植统一验证

## GTNH 2.9.0 正式版环境迁移

- GT5 5.09.54.265 固定提交 bb00f3f1023e20d3cce84cf09abb442d22a3a5a5：GTFoodStat 和 MetaGeneratedItem 与原基线源码及实装类字节一致，保留现有食物字段适配
- Backhand 1.8.18 固定提交 a18c3ab5dc15009beeb45716c0493912be55dbca：核对实装常用副手 API；不借此声明玩家交互或女仆盾牌组合已通过
- Angelica 2.2.43 固定提交 2b46b4f602dfd7c6b5dd67f42acd853adfa1d9c6：反射矩阵、客户端纹理单元与绘制计数分别改从
  GLSMMatrices、GLSMTextures、GLSMDraws 读取，保留旧 API 回退
- Et Futurum 2.6.65-GTNH 固定提交 ff1a4717b22fa16da0d986deb3c59d0d4c9ff9db：核对变更路径，旁观保护仍须在正式版实机确认
- 正式版依赖审计与运行证据单列，旧 RC2 结果不迁为正式版通过

## 受伤事实与活动中断

- 固定上游 TLM 的 MaidPanicTask、MaidClearHurtTask、MaidRunAwayTask 与 MaidBrain.registerPanicGoals：受伤触发 PANIC 活动，逃离点仍来自
  NEAREST_HOSTILE，不凭伤害来源推断不可见攻击者的位置
- MC 1.20.1 原始 client JAR 的 HurtBySensor 与 LivingEntity.getLastDamageSource 已按官方映射和实际字节码核对，最后伤害来源在世界时间差大于 40 tick 后清除
- 新增 com.tianluoqaq.tlmnh.common.ai.perception.MaidHurtMemory，记录原生接受攻击后的短期事实；世界变化、回退时间与策略生命周期取消清除，不进入存档
- 原型按事实到期直接结束中断，尚未声称与现代 HURT_BY 传感器分频相位逐 tick 一致；完整忠实 F 与增强 N 继续独立待验

## 低氧保全与水中移动

- 固定上游 8a3ac5e 的 entity/ai/brain/task/MaidBreathAirTask.java、MaidBreathAirStopTask.java：低于 100 空气启动、恢复到 290
  或获得水下呼吸效果后结束；先检查双手与背包的呼吸物品，再找可呼吸位置
- 同提交 entity/passive/MaidPathFindingBFS.java、entity/ai/control/MaidMoveControl.java 和
  EntityMaid.travel/isPushedByFluid：核对三维搜索、0.5 目标速度、三倍基础前进、水中加速度及 0.9 阻力；新增独立 MaidBreathController
  适配旧版服务端生命周期和碰撞移动，未整文件改包移植
- 普通饮用药水读取目标 ItemPotion.getEffects，拒绝喷溅药水，按原生效果 NBT 应用，饮用结束才消耗一份并归还玻璃瓶；GT 呼吸食物沿用已核食物效果适配，不执行玩家回调
- Root 优先交接低氧保全，当前仍是原型中的保全策略；暂未声称完整忠实 F 的物品使用等待相位或全部现代游泳姿态/碰撞箱一致
- 空气搜索以现有实体尺寸核对已加载方块及碰撞，水平/垂直范围各 16，每 tick 最多处理 128 节点，不加载区块；这些是本项目适配边界，不能据此声称完整世界级调度预算已完成

- 实际封顶水域暴露了旧引擎差异：1.7.10 EntityLivingBase.onLivingUpdate 将小于 0.005 的速度归零，1.20.1 对应阈值为 0.003；原版水中水平余速约 0.0045 在旧版无法积累
- 新增条件 Mixin，仅对正在执行呼吸路线且位于水中的女仆使用 0.003 阈值，其余实体和普通女仆仍返回原值；不提高水中目标速度、不改全局碰撞或其他生物规则

## 弓手横移与追击蓄力

- 固定 TLM 提交 `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6` 的
  entity/ai/brain/task/MaidAttackStrafingTask.java:54–105：每 20 tick 各以 0.3
  概率翻转方向，距离大于弓默认射程一半时前进、小于五分之一时后退；主人范围边界抑制走位，停止时清输入但保留任务方向状态
- 新增独立 MaidStrafingState 投影该方向状态，原始文件未改包复制；MC 1.20.1 实际 BowItem 字节码的默认范围为 15，MoveControl.strafe 使用 0.25
  倍移动属性并在不可走侧向时退回前方
- MaidBrainCoordinator 与 EntityMaid.moveEntityWithHeading 适配旧版没有 strafe 控件且原生 move helper 会清前进输入的差异：仅当前世界
  tick、有效 COMBAT 身份可投递一次输入，保留原生加速、摩擦和碰撞，不直接改位置
- 地形守卫使用已加载方块、身体碰撞、下方支撑、家园范围及液体/火/仙人掌排除；前方也不可走时停下，这是目标环境的保守适配，不声称等价于全部现代 WalkNodeEvaluator 类型
- MaidShootTargetTask.java:72–113 的行走/使用物品并行关系用于修正追击时每 tick 清空蓄力的问题；隐藏目标仍按真实 canStillUse 可见性条件取消，未套用脱离 Behavior
  生命周期的 60 tick 保持假设
- 当前仍保留原型的 12 格发射半径、目标选择、路径与冷却门槛；完整原版走近/家园语义、原版射击 profile、完整 F/N 和原版 benchmark 均未由本批完成

## 世界级导航预算

- MaidNavigationBudget、MaidWorldNavigationBudget 和 MaidNavigationConfig 是本项目独立实现，不是上游 TLM 的原版行为；生产导航显式启用，原版对照核心的调用节奏保持独立
- 现有 MC 1.7.10 PathNavigate/PathFinder 的创建与安装仍由 MaidBoundedPathPlanner 适配；新增准入只限制每世界每 tick 的原生调用数，不能限制一次 A* 内部耗时
- FIFO 队列只保存女仆 UUID 与最后轮询时间，获准后调用方读取当前目标重新创建，不延迟执行旧世界回调；取消不退回已用额度，过期身份和世界时间回退可清理
- WAITING_BUDGET 与 STALE_REQUEST 不作为不可达，不清已有路径或加入失败缓存；生产安装另核创建时的世界、实体、主人、任务和策略身份，失效不获得新授权
- 默认每世界每 tick 8 次是 Indev 可配置起点，范围 1～256、重启生效，尚未称为性能最优值；原版测评、感知预算、失败缓存、完整 F/N 和最终长时压力验收继续待办

## 普通动作共享意图与滞回

- MaidIntentFrame 和 MaidIntentArbiter 是本项目独立实现；低氧、原版 panic 和战斗仍先按已有硬分支抢占，普通动作才在指令、照料和自主生活分组内选择
- 意图不授执行权限，每帧持有独立身份；旧帧、旧策略 token 或已不合法的目标不能因历史高分继续执行，动作和物品仍由原有 runner/lease/commit 边界核对
- 生活候选的原版范围、作息、STEAL/PLACE 交替及真实床/食物操作沿用前述固定上游来源；新增只读准备和明确采用入口，原版对照选择器仍独立保留
- 组内距离效用为 `1 / (1 + distance)`，最短保持 20 tick、切换差 0.05，仅作为 Indev 起点；更高分组和当前目标失效立即重新选择，未称为已校准的智能程度或性能收益
- 返家、跟随、休息、偷吃和拾取的普通竞争已接 Root，但完整 F/N、全部现代核心语义与原版 O/F/N 仍需后续验收，不能把原型重命名充数

## 游泳姿态、尺寸与被动跳跃

- 固定上游 `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6` 的 entity/passive/MaidSwimManager.java 原包名保留，平台类型改为独立
  actor；服务端按想游泳、落地、坐下和骑乘更新标志与实际姿态，停止游泳不覆盖睡眠姿态
- entity/ai/brain/task/MaidSwimJumpTask.java 保留原包名；其原生 Swim/Behavior 生命周期映射为显式世界时间、流体、导航和两路 RNG 输入，仍使用 60 tick
  生命周期和严格小于 0.8 的跳跃概率
- EntityMaid.java:1818–1819、2652–2653、2635–2648 的眼高、游泳尺寸和主动游泳 travel 为适配依据；MC 1.20.1 实际
  Entity.refreshDimensions、LivingEntity 眼高及 CollisionGetter.findFreePosition 字节码补齐引擎行为，尺寸增长以方块碰撞体膨胀后查找自由中心
- MaidEntityPose、MaidPoseDimensions、MaidAquaticController 与两个 actor 是本项目独立文件；实际姿态使用 DataWatcher 同步，避免旧 setSize
  在宽度增长时平移实体
- MixinEntityAquatic 只为小于 0.8 高的女仆修正水检测框，现代 Entity.updateFluidHeightAndDoFluidPushing 使用完整身体内缩 0.001；旧版上下各减 0.4
  会倒置 0.6/0.2 高身体的范围，原生水流、入水效果和其他实体保持原方法
- 当前旧导航上的模式判断与主动水中执行只是执行投影；完整现代 Amphibious 节点器、全部导航管理器语义与非主动游泳的现代 travel 仍待实现，不能据此称完整水域或完整 F 已完成

- EntityMaid.setScaleForAge 阻止旧 EntityAgeable 客户端每 tick 以年龄重新设置站立尺寸；仅在尺寸与同步 Pose 不同时恢复，保留现代眼高缓存的刷新边界

## 默认水中非主动移动

- Indev95 依据同一固定上游 EntityMaid.java:2635–2648 的非主动游泳分支，将水平输入乘 1.2，并在准备上岸或头部入水时增加 0.5 垂直输入，否则增加 0.05
- MC 1.20.1 实际 LivingEntity.travel/getFluidFallingAdjustedMovement 字节码用于核对三轴归一化、0.02 默认加速度、0.8 阻力、0.08/16
  默认水中重力、奔跑和墙边脱困；新增独立 MaidWaterPhysics，不把原生水平二维加速和 0.02 水中重力当作现代行为
- 固定上游 MaidNavigationManager.switchToNavigation 的无路径分支切换导航但不写 mode；原版 tick 随后按 GROUND 取消 wantToSwim，旧投影修正为无路径闲置不锁定主动水中模式
- 正式版 Et Futurum 2.6.65-GTNH 的 living-base 鞘翅钩子和水相关代码已核对，未补足这段非主动水中 travel；这不等于审计了整包所有水中附魔或重力来源
- 当前仅接默认物理，现代特殊附魔/效果、环境重力、完整 Amphibious 节点器和导航切换仍待补齐；服务端真实碰撞实测不替代自然寻路、客户端或完整原版 benchmark

## 单目标两栖路线与执行

- Indev96 移植固定 TLM `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6` 的
  entity/ai/navigation/MaidUnderWaterNodeEvaluator.java，保留原包名；使用查询局部位置集合适配已访问节点，完整快速可达缓存接口尚未接入
- 同提交 MaidSelectivePathFinding、MaidUnderWaterBoardingPathFinder 用于核对目标无流体时的上岸启发式：节点低于目标时额外计入每格 100 的高度差，目标仍记录最近搜索节点
- 实际 MC 1.20.1、Forge 47.2.0 映射类
  WalkNodeEvaluator、AmphibiousNodeEvaluator、PathFinder、Node、BinaryHeap、PathNavigation 与 AmphibiousPathNavigation
  用于核对占据范围、类型代价、台阶/下落/转角、上下水节点、浅水偏好、1.5 启发权重、严格堆比较、部分路线与三维推进/watchdog
- MaidPathContext/Region/Type/Node、MaidWalkEvaluator、MaidAmphibiousEvaluator/Search/Path 和 MaidPathNavigator
  为独立实现；水中生产查询使用新搜索和执行，地面仍保留既有原生查询/执行，共用同一导航身份以维持动作的路线所有权
- MixinEntityNavigator 仅在女仆构造时替换 EntityLiving 的实际 navigator 字段，内部直接字段调用与 getNavigator 保持同一个实例；其他实体不替换
- 允许跌落高度读取实际 EntityLiving.getMaxSafePointTries；已核对它与现代 Mob.getMaxFallDistance 的无目标/生命/难度公式一致，不将普通情况下的 3 格写死到战斗场景
- 目标世界按实际方块、门/栅栏/轨道、流体及碰撞读取；Et Futurum 2.6.65-GTNH 的 BlockHoney、BlockMagma、BlockWitherRose 分别映射蜂蜜、火伤和谨慎伤害类型，不引入新硬依赖
- 当前仅单目标，仍受原型每次 16 格查询范围和世界准入预算约束，部分路线不谎报 canReach；完整现代地面节点、导航管理器切换、缓存、多目标/API 相位和原版运行对照继续待办，完整 F/N 不启用
- 旧 World 没有现代生成器海平面 API，当前使用主世界 63；特殊维度海平面、复杂模组方块与完整环境适配仍待验，不能把有限水池结果泛化为全部地形通过

## 地面专用节点、导航模式与移动控制

- Indev97 移植同一固定提交的 entity/ai/navigation/MaidNodeEvaluator.java、entity/passive/MaidNavigationManager.java、
  entity/ai/control/MaidMoveControl.java，保留原包名；平台事实分别由 Region、NavigationActor 和目标原生实体提供
- 地面节点补入家园限制、独立木门/栅栏门权限、启用攀爬时的上下节点和自身零食柜避让；现代可选外部模组标签尚未完成目标映射，不猜测旧物品注册名
- 管理器依照 EntityMaid.java:617–620 在原生 living 更新后运行，保留有路径时重规划、检查 canReach、推进首节点及无路径切换不写 mode 的实际分支
- 同一 MC 1.20.1、Forge 47.2.0 映射包的 GroundPathNavigation、MoveControl、Mth 用于核对地面目标归一化、浮水临时高度、
  移动操作生命周期、90 度转向及查表 atan2；横移检查单格类型，不混用寻路身体占据类型
- MaidPathSearch、MaidModernPath、MaidNavigationActor、MaidPathOwnership、MaidMovementAngles 为本项目独立文件；
  旧 AmphibiousSearch/Path 保留水查询兼容入口，地面和水中状态各自维护路径、速度及 watchdog
- 动作面对同一个导航器；模式续接保留原动作的取消凭据，未沿用凭据的无关路线不能由旧动作撤销；凭据不授予新动作执行权限
- MixinEntityNavigator 同时替换女仆实际 navigator/moveHelper 字段；主动游泳由原移动控制器施加转向和速度，删除旧水中投影的重复加速
- 移动控制器对目标距离恰为零增加停止保护，避免原算法除零；原型低氧的独立执行器暂由 AquaticController 桥接，不宣称已接完整现代呼吸任务
- 新控制器在原型低氧执行器持有移动时让出驱动；低氧路线每 tick 重核终点的当前眼高与空气事实，避免站姿规划的终点在泳姿下仍处于水中
- 站姿浅水回归暴露旧水检测上下各缩 0.4 的另一影响，Indev97 将 MixinEntityAquatic 的完整内缩身体扩展到所有女仆姿态；其他实体及目标世界的原生流体/水流方法保持，不宣称已移植完整现代流体系统
- 仍缺完整动态 Brain WALK_TARGET、MoveToTargetSink 相位、快速缓存、多目标和完整攀爬任务；窄水槽的 WATER_BORDER 竖直恢复也须处理，完整 F/N 与自然原版对照继续待办

## 现代导航与 Follow 核心组件

- Indev98 继续采用固定 TLM 提交 `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6`，核对
  MaidFollowOwnerTask、MaidPathNavigation、MaidUnderWaterPathNavigation 与既有 MoveToTargetSink/Behavior/EntityTracker 映射字节码
- 现代 PathNavigation.createPath 按独立 targetPos 元数据复用活路径；新查询即更新该元数据并重置超时，即使返回路径最后未安装，不能用已安装结果的目标代替此相位
- ModernFollowCoreActor/Component 是本项目新增适配文件，接实际现代导航、动态主人目标、PATH、真实 canReach 和停止事实；现有 FollowOwnerTask 移植文件保留原包名和来源
- 创建/安装之间的模式、tick 和策略 lease 校验及身份取消属于本项目生命周期边界，不能冒充上游原有 API；泳姿生命周期清理只允许重置两状态均无路径的管理器
- 原版 MoveToTargetSink 的 150～250 tick 时限保留，重规划不延长时限；动态跟随停止不一律等于已到达，watchdog 必须从实际导航记录判断
- 独立 QA 夹具使用 Forge FakePlayer，并把它登记到真实全局在线列表；核对目标 ServerUtilities 2.4.22 的 Universe.getPlayer/ServerUtils.isFake 分支，避免普通合成玩家缺失登录档案造成启动崩溃
- 本批是 Follow/Move/Look 组件接线；完整感知、Brain 活动和工作记忆、快速可达缓存/多目标、完整 F/N 与原版 O/F/N 继续待办

## 导航多目标与可达 BFS

- Indev99 从同一固定 TLM 提交移植 entity/passive/MaidPathFindingBFS、util/CenterOffsetBlockPosSet、entity/ai/navigation/ICachedEvaluator，保留原包名与来源头
- BFS 保留中心/竖直半径、候选点或其上方已访问即通过、增量队列及水节点访问排除；`isVis` 域外返回 true 是上游事实，不擅自改成 false
- 平台适配将 BlockPos/Node 换为现有整数 Point/现代节点与 Region 边界，新增 AutoCloseable 和幂等释放，Evaluator.prepare 清除前次节点及类型缓存
- 多目标 A* 对照 MC1.20.1 映射 PathFinder，首个命中集合按节点数选结果，部分路线按末节点 Manhattan 距离再节点数选；同键目标按整数值去重
- 核对 MaidSelectivePathFinding/MaidUnderWaterBoardingPathFinder，任一目标流体为空即对整个查询采用上岸启发式；相同评分的目标不承诺跨平台固定选择次序
- 集合查询按 PathNavigation 原重载直接使用目标坐标，单坐标入口才执行 GroundPathNavigation 的归一化；不把目标元数据缓存误写成已安装路线目标
- 这些查询基础尚未完成原 MaidBreathAirTask/生活任务接线、全活动和感知，也不等于新增原版自然运行对照或完整 F/N

## 原呼吸任务接现代组件

- Indev100 从固定 TLM 提交移植 MaidBreathAirTask/StopTask，保留原包名与顺序；现代 Behavior 的默认 `nextInt(1)` 开始相位由外层组件消费
- 任务条件保留食用中拒绝、有效空气目标与活路径复用、100 氧阈值、呼吸效果和使用中检查；结束保留 290 氧/效果/饰品条件及目标记忆擦除
- BFS 搜索范围和竖直范围均为 16，找到目标后仍用实际现代 A* canReach 确认；失败后按当前位置海平面中心、再按原 5×5 迭代顺序尝试
- MaidAirActor 为本项目新增世界/物品/记忆边界，现有物品控制器负责已核手/背包/临时交换与使用生命周期；尚未接原请求物品事件或溺水饰品槽/物品
- 原 givesAir 实际判断目标单格的空流体或气泡柱以及空碰撞形状，未按注释改成两格空气，也未沿用原型按当前眼高验证终点的另一算法
- 目标 Et Futurum 2.6.65 的 BlockBubbleColumn 碰撞为空且对 Living 实体恢复 300 氧，按已核实际类识别；现代 MobEffectUtil 还检查 CONDUIT_POWER，该效果目标映射仍待办
- 原 TARGET_POS 工作记忆、完整感知/活动与原攀爬任务尚未接本组件，完整 F/N 和新增原版自然运行对照继续待办

## 原攀爬与物理相位

- Indev101 从固定 TLM 提交移植 entity/ai/brain/task/MaidClimbTask，保留原包名和来源；外层组件负责原默认 Behavior 的 60 tick 期限及 `nextInt(1)`
- 核对原 EntityMaid.onClimbable/handleOnClimbable/handleRelativeFrictionAndCalculateMovement/baseTick，
  保留路径窗口、脚下梯子、居中/水平归零和 30 tick 跌落距离复位
- MaidClimbActor 为新增平台边界，帧内路径 view 保留端节点身份，修改索引前核当前策略、tick 与路线；取消按现有 Brain 绑定身份清理，保留外来后继状态
- 目标 EntityLivingBase 地面第三次 moveEntity 和第二次 isOnLadder 分别接居中与抑制原生上冲，重定向仅对现代组件生效；服务端实际加载验证注入匹配
- 已读目标 ForgeHooks/BlockLadder 与固定现代 ForgeHooks/ForgeConfig.Server 字节码，现代默认 fullBoundingBoxLadders=false，逐格调用 BlockState.isLadder
- 实测 QA 运行字段为 true，尽管文件配置 false，导致在邻格触发后居中回原格；现代分支用当前/下格的 Block.isLadder 映射默认合同，全局字段/其他实体不改，未宣称已查明运行值覆盖来源
- 运行类导出和相关 GTNHLib/BugTorch/Catwalks 字节码仅作诊断证据；不将读取、Mixin 加载或局部服务器夹具等同完整原版运行相等
- 地形场景区分三格安全跌落与六格强制下梯，保持实际跌落能力与算法；复杂模组梯子、全部动画和完整 F/N/原版 O/F/N 仍待验

- Indev102 从固定 TLM 提交移植 `MaidNearestLivingEntitySensor` 与 `MaidHostilesSensor`，保留原包名与来源头；适配为目标版实体查询与现代组件持有的记忆
- 实读原 `MaidBrain` 感知先于行为、`EntityMaid.searchDimension`、任务搜索盒与弓默认配置；普通扫描竖直范围 4，弓任务只在主手有弓时改为同半径立体盒
- 固定 mapped 1.20.1 的 `Sensor`、`TargetingConditions`、`NearestVisibleLivingEntities`、`Brain` 与 `LivingEntity` 字节码
  用于核对 20 tick/独立随机相位、惰性缓存、16 格/隐形护甲与攻击目标分支；未新增原版自然运行成绩
- `MaidModernPerception` 与 `MaidVisibleLivingEntities` 位于自有根包，保留正阴性缓存到下次扫描；原生苦力怕类型在目标版映射为原生运行类，未将全部 IMob 当作原版危险
- 目标版 EntitySenses 提供真实视线；现代 Forge 可见性事件尚无同名目标接口，完整感知/活动消费者及原版运行对照仍待完成

- Indev103 从固定 TLM 提交移植 `MaidPickupEntitiesSensor` 与 `MaidPickupEntitiesTask`，保留原包名与 MIT 来源头
  保留原清单过滤顺序、30 tick、优先级 10 和任务局部 BFS
- 任务 BFS 使用已移植原查询，默认半径为当前任务 searchRadius、竖直 7，动态实体 WALK/LOOK 与实际接触交易分开；未复制新的一套库存事务
- 固定 mapped `EntityTracker`、`BehaviorUtils`、`HurtBySensor` 与 `LivingEntity` 字节码用于核对目标位置/眼高、可见记忆和受伤寿命；新增适配位于自有包
- `MaidDamageHistory` 只记录目标版真正接受的 DamageSource；策略取消不擦实体历史，NBT 重读/换世界/时钟回退失效，超过 40 世界 tick 清理
- HurtBy 的环境伤害/空源与旧攻击者保留相位，以及活体 LOOK 的可见缓存依赖均按读取合同适配；完整活动与原版自然运行对照继续待办

- 拾取夹具同时核对目标版 `EntityItem` 半高 yOffset 与 Horizon-QA 出生位置 API，使用有效出生高度和实体实时坐标；实际观察失败与定向纠正单独保留

- Indev104 从固定 TLM 提交移植 Panic、ClearHurt、Await、RunAway/SetWalkTargetAwayFrom、UpdateActivityFromSchedule 与 ClearSleep
  保留原包名及 MIT 来源头
- 已实读原 Brain 优先级、Task 的 enablePanic 与坐骑危险离座条件、三种日程；当前首版保留任务没有 workPointTask，特殊多乘客载具接口未引入
- 固定 mapped LandRandomPos/RandomPos/GoalUtils/Behavior/Brain/Activity/BlockPosTracker 用于核对候选、生命周期及活动组遍历，新增适配位于自有包
- Activity 的 hashCode 来自名称字符串，优先级 99 按原注册顺序放入同等 HashMap；保留同帧切换后后续活动组再次检查，而不是固定 CORE 先于活动
- 原 BlockStateBase.calculateSolid 默认按碰撞包围盒平均尺寸或高度判断，目标版据实际 AABB 映射；现代强制 solid 属性尚无同名目标元数据

- 补读原 `SchedulePos` 确认独立的 40 tick 实体侧限制区/返回/传送流程；本批仅原日程活动 Task 与既有锚点适配，该实体流程及限制区锁存留作下一批

- Indev105 从同一固定 TLM 提交移植 `entity/passive/SchedulePos` 与 `util/TeleportHelper`，保留原包名和来源头
- 实读原 EntityMaid 的 RESTRICT_CENTER/RADIUS 初值、aiStep 调用位置和限制区判断；实体后移动阶段每 40 tick 施加锁存限制区，独立于 Brain 的日程活动节流
- 原非家园范围默认 8，关闭 home 后保留最后运行限制区；目标版复用现有 DATA_HOME 字符串后缀同步，不新增槽位或改变旧 NBT Settings 格式
- 固定 mapped LivingEntity.randomTeleport、事件 46 与 Entity 位置更新字节码，核对整数候选、下探支撑、真实碰撞/液体、失败回原位和 128 个粒子的抽样顺序
- 原 TeleportHelper 初步方块读取可加载目标区块，randomTeleport 自身再检查区块；不替换为主人传送算法，也不在十次失败后发明 WALK 回退
- 新增限制区/传送适配位于自有根包，沿实际碰撞 AABB 映射原默认 solid/blocksMotion；现代强制 solid 属性及特殊模组方块兼容仍有限制
- 这些源码与字节码合同不等于新增原版自然运行或 O/F/N benchmark，完整 F/N 仍未开放

- Indev106 从固定 TLM 提交移植 `MaidCheckRateTask`、`MaidHealSelfTask`、`MaidWorkMealTask`、`MaidRequestItemEvent` 及 `ItemsUtil.findStackSlot`，保留原包名和来源头
- 实读原 Brain 核心治疗优先级 3、WORK/RIDE_WORK 各自的工作餐优先级 7，检查计数按 50 加实体 RNG 的 0～49，并保留睡眠、缺血至少 2 和工作好感冷却条件
- 实读原 DefaultMaidHealSelfMeal/DefaultMaidWorkMeal、HandUtils 和库存临时换手，已有目标版营养/效果/容器适配继续由同一控制器执行，不增加第二份隐藏库存
- 固定 mapped LivingEntity.startUsingItem 的已使用分支直接返回，因此同帧两个 meal 回调各自施加开始效果，共享的使用时长不能重置或消耗两份物品
- 原请求事件的 ItemStack 仅表示已转入库存的回执；ItemsUtil 先查背包，缺少才发事件，再查实际库存，目标版在回调后另核当前策略授权
- 旧 Forge 用可取消事件和 null 空栈适配，未增加其他内容模组的外部存储联动；现代呼吸复用请求，原型呼吸保留旧入口
- 未映射的现代饰品食用回调、完整持续进食粒子以及未知自定义食物仍有缺口，以上接线不代表完整 F/N 或原版自然 benchmark

- Indev107 从固定 TLM 提交移植 `MaidFollowOwnerVehicleTask`，保留原包名、来源头及核心优先级 3 的注册顺序
- 实读原 PlayerMixin/IPlayerMixin 的移除载具时间戳、MaidConfigMessage 的骑乘开关与下船、原界面 51/206 的骑乘按钮
  以及 MaidConfigManager 的顶层 `MaidIsRideable` NBT 和默认 true
- 原离船窗口按 Util.getMillis 的单调 3000 毫秒处理，目标版在实际玩家 mountEntity 入口记录，不替换为 60 个世界 tick
- 实读目标正式版 EFR 2.6.65 源码提交 `ff1a4717b22fa16da0d986deb3c59d0d4c9ff9db` 的新船、座位和箱船 API，并核实装 JAR 与座位开关
  用 getPassengers/getControllingPassenger/getSeat/addToBoat 和 Seat.getBoat 适配容量、驾驶权及代理身份，不用 sitEntity 挤出主人
- 自有 Actor 的载具引用绑定当前 token/世界及实体 tick，动态 WALK 仍用已有 EntityTracker 路径；回调取消后旧引用不能因新帧重新授权
- 现有 31 号字符串追加版本 4 骑乘列，兼容 1/2/3 默认值并保留频率、拾取、导航和显示字段；没有新增 DataWatcher 槽或自创配置页行
- SWarfare、未移植的 EntitySit/飞行扫帚例外、完整原 rideTick 朝向及客户端乘客表现不在本批完成范围，完整 F/N 和原版自然测评继续待办

## 原床、乞食与工作目标

- Indev108 从固定 TLM 提交移植 `MaidBedTask`、`MaidBegTask` 与 `MaidRideBegTask`，保留原包名和来源头；`MaidCheckRateTask` 补回可设置的下次检查计数
- 实读原 Brain 的 REST5、IDLE5/WORK6 与 RIDE_IDLE4/RIDE_WORK6 注册、跨活动运行行为；原床 POI 只登记 HEAD 且未占用的状态
- 实读原 EntityMaid 的睡眠全回血/好感、限制区、搜索点及诱惑物配置解析；参考固定映射原生 LivingEntity 的睡眠/床存在检查、PoiManager 的整数球形范围
- 自有 `MaidLifeActor`、`MaidModernLife` 适配帧守卫、独立工作记忆、已加载区块床头索引和既有床租约，不搬用原型生活选择逻辑
- 1.7 目标缺少现代 POI 持久记录，当前不加载未加载床区块；醒来用本平台站立碰撞挑邻位，现代原生出口逐项等价尚未核验
- 诱惑物支持原注册 ID/默认蛋糕，现代标签无对应时按未知项回退；乞食仍只检查主人主手和已缓存可见实体
- 已实读后续偷吃移动/使用、父 MoveToBlock、CakeEdible 与 IMaidEdibleBlock 的关键合同；本批只接床和乞食，偷吃动作记忆与原两阶段尚待移植，不以源码阅读冒充已实现

## 原偷吃移动、使用与动作记忆

- Indev109 从固定 TLM 提交移植 `MaidMoveToBlockTask`、`MaidStealEdibleMoveBlockTask`、`MaidStealEdibleUseTask` 和 `MaidEdibleBlockAction`，保留原包名/来源头
- 实读原 CakeEdible、IMaidEdibleBlock、MaidEdibleBlockManager、MaidPathFindingBFS、EntityMaid 的可用库存与放置入口、MaidInvWrapper 的用途；首版只接原生蛋糕和最小零食柜，不引入其他食品扩展
- 原移动任务的搜索顺序、最后工作点、主人严格 8 格、27 邻点 BFS、成功搜索 900 计数与动作交替保持；Use 的工作目标/WALK 精确中心检查与清理保持
- 自有 `MaidStealActor` 与 `MaidCakeAction` 负责目标世界查询、有限库存、原生 Forge 事件和回调提交守卫；双手先于背包，不读取护甲作食物
- 目标 MCP BlockCake 的 metadata+1 达到 6 时移除，采用本平台六口而非现代七口；粒子发送走目标 WorldServer 原生包，实际客户端表现待验
- 放置继续检查零食柜与资源身份，比源 Use 的位置复核更严格；近处已失效的源工作目标没有擅自加入自动清理，后续增强策略单独处理
- 初生首次搜索与稳定落地后站起的结果不同，原始失败和隔离诊断保留，未用手动搜索冒充自然通过；原版首帧合同仍待对照

## 原选敌、近战与盾牌活动

- Indev110 从固定 TLM 提交移植 `IAttackTask`、`TaskAttack`、`DefaultMonsterType`、`MonsterType`、`AttackListData`
  以及 `MaidHurtTarget`、`MaidMeleeAttack` 和 `MaidUseShieldTask`，保留原包名、来源头和行为条件
- 实读原 EntityMaid 的攻击属性、距离、伤害事件、任务刷新记忆、目标 getter 与可用盾牌；实读 TaskData 注册及 Codec 存档
  保留 `MaidTaskDataMaps/touhou_little_maid:maid_attack_list/attack_groups` 结构与未知数据
- 实读固定 1.20.1 映射字节码的 StartAttacking、StopAttackingIfTargetInvalid、SetWalkTargetFromAttackTargetIfTargetOutOfReach
  以及 OneShot、ExpirableValue、Brain 的过期顺序及 BehaviorUtils 攻击范围
- 自有 `MaidModernCombat` 保留优先级 5 的选敌、失效清理、步行追击、近战、举盾顺序；骑乘版本省略追击，OneShot 不添加 Behavior 的随机时长抽样
- 自有 `MaidCombatMemory` 按实际 Brain 帧过期，任务取消保留攻击目标与冷却；目标 view 绑定当前策略 token、世界 tick、实体年龄及目标身份，原 getTarget 语义经实体 getter 桥接
- 自有 `MaidCombatAttributes` 默认速度 4、距离 2，加原好感距离；使用目标平台真实物品修饰符、伤害、附魔与耐久，不虚构现代武器速度修饰符
- 实读正式版 EFR 2.6.65 的 EntityArmourStand 和原生 FML 注册查询；分类使用实际 IMob/INpc、驯养归属、原生受伤/攻击历史与配置，不把所有模组实体类型宣称为现代 Enemy 的一一映射
- 现代 Forge 的可取消 BEHAVIOR_TARGET 事件以自有 `MaidAttackTargetEvent` 适配；原 Pre 取消仍为 API true 且无 Post/损耗，回调后继续检查实际授权，致死已接受攻击仍执行原武器损耗
- ShieldController 拆分 owner 限定的启动与运行结束，保留原型旧入口；普通目标近战参考位置已适配，现代 RiderShieldingMount 特例、分类 GUI/网络和完整任务刷新工作记忆仍待补齐

## 原随机活动与雪球

- Indev111 从固定 TLM 提交移植 `MaidRunOne`、`MaidStartSnowballAttacking` 与 `MaidSnowballTargetTask`，保留原包名和来源头
- 实读原 TaskIdle 的冷群系/雪层/主人与同主目标，MaidBrain 的 IDLE/WORK/REST/RIDE 组优先级、张望类别和抽样权重
- 实读固定映射 GateBehavior、RunOne、ShufflingList/WeightedEntry、SetEntityLookTarget、DoNothing
  以及 RandomStroll 陆地变体、LandRandomPos/RandomPos 相关采样与 Biome 冷雪阈值，独立随机源与浮点指数保持
- 自有 `MaidRandomGate`、`MaidRandomActor` 适配原 Gate 生命周期；`MaidLandRandomSampler` 新增无方向采样，共用原稳定/限制/上移/拒水/成本检查
- 自有 `MaidSnowballActor` 复用 guarded 攻击目标引用，原 StartSnowball 不走战斗选敌事件，停止按身份清自身目标
- 目标平台 LOOK 的猫按原生 Ocelot 类型适配，没有对应原生 Parrot 时保留该权重空项，不扩大为其他模组动物
- 实读原 MaidAnimationMessage、AnimationManager misc/playAnimation、RawAnimation 相等与 AnimationController.setAnimation
  原连续拾取只延长计时，未在雪球分支使用 shouldReset；撤掉额外强制重播，保留空动画重置
- 自有 `MaidSnowballAnimation` 保存非 NBT 计时与观测序号，用实体事件 47 向实际跟踪端同步，不强行在 Bedrock 新增原源没有的特殊动作
- 实读正式版 EndlessIDs 1.7.4 的 ChunkBiomeHook JAR API，HQA 使用 getBiomeShortArray 并恢复原数组；生产不引入该测试依赖
- 原版随机输入复现、第一次 650 tick 超时原因、完整任务刷新工作记忆、特殊座椅和实际 Gecko 雪球画面仍待后续验收

## 原弓箭活动与目标平台箭执行

- Indev112 从固定 TLM 提交移植 `IRangedAttackTask`、`TaskBowAttack`、`MaidRangedWalkToTarget`、`MaidAttackStrafingTask`、`MaidShootTargetTask`，保留原包名与来源头
- 实读原选敌先 lastHurtBy、再 NEAREST_LIVING 的顺序；远程 TargetingConditions 每帧独立使用配置范围、隐身/盔甲和原生视线，不借用固定 16 格可见缓存作选敌列表
- 实读原 Shoot 的任意物品使用检查、停止使用后发射、20 tick 蓄力和属性冷却；共享使用计数在 living 开头推进，默认使用速度 1、射击冷却 2
- WORK/RIDE_WORK 独立持有射击 Behavior；地面组增加原 Walk/Strafe，骑乘组没有它们；1200 tick 期限、原活动切换后运行保留和停止重置规则保持
- 实读映射 BowItem 的默认射程 15、Mth.rotateIfNecessary 的零角限制及 LivingEntity 可见性条件；走位使用已有 MaidMoveControl 的实际 STRAFE 操作
- 自有 `MaidModernRanged`、`MaidRangedActor` 适配相位与当前帧目标；持续使用租约绑定策略代数、主人、任务和手持身份，不按上一帧 tick 自动失效
- 与原型共用原生箭求解、安全检查和生成后资源结算；原型保留 12 格、独立 draw20、移动提前量与暴击规则，现代入口不继承这些准入
- 本批目标平台基础箭固定速度 3、无随机散布、无额外暴击，补偿原生重力/阻力并瞄准目标当前中心，保留 Power/Punch/Flame/Infinity、双手优先弹药和原好感基础伤害倍率
- 原 O 的无重力、距离相关速度/散布、现代箭随机基础伤害与该目标平台执行配置不同；此处不是 O 等价证明，也不是完整 F/N 或同物理 F-R/N 收益结论
- 当前武器支持目标平台 ItemBow 与原生基础箭；QuickCharge 默认适配为 0，实装 JAR 名称扫描未发现对应符号，不据此声称所有模组都没有语义相似附魔
- 全工作记忆刷新、真实 GUI Brain 暂停、完整分类界面、现代额外弹药/自定义箭接口及完整原版对照仍待后续实现或验收

## 原 Brain 刷新与界面暂停

- Indev113 实读固定 TLM EntityMaid.refreshBrain/setTask/setSchedule、MaidBrain.registerBrainGoals 与 AbstractMaidContainer 的打开/关闭状态
- 实读固定映射 Brain.stopAll/copyWithoutBehaviors：仅停止运行行为，并将仍存在的记忆值交给重建后的 Brain，不把刷新当作策略取消
- 自有 ModernFollowCoreComponent 执行运行中 Look/Move/Climb/Beg/Shield/Strafe/Shoot/Snow/Vehicle 的停止效果，
  再重置检查计数、独立随机 Gate、射击/走位及 Sensor 初始延迟
- 工作点、偷吃动作、攻击目标、攻击 TTL、不可达时间与感知记忆按剩余值保留；运行中 Move/Look 的停止会擦相应记忆，Snow 的停止会清自身攻击目标
- 相同任务不刷新，日程设置按原版每次刷新并立即初始化活动；停止逻辑先在原帧授权下执行，行为身份随后作废，旧动作引用不能在刷新后的同帧恢复权限
- 原 guiOpening 只跳过 Brain tick；共享物品使用和实体 SchedulePos 保持 living 相位，界面暂停不复用会撤销策略与资源的开发 suspension
- 实读 Forge 1.7.10 FMLNetworkHandler.openGui 的真实字节码：先 getRemoteGuiContainer，再 closeScreen，随后安装新窗口
- 目标平台用窗口对象身份释放暂停状态，避免旧窗口关闭清除刚构造的新窗口；该身份不存档，也不作为 GUI 布局或网络验收的替代
- 其他现代工作点任务、特殊坐骑分支、完整 F/N 策略、真实客户端交互与原版自然 benchmark 仍按首版范围继续验收

## 原门 OneShot 与关门记忆

- Indev114 移植固定 TLM `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6` 的
  `entity/ai/brain/task/MaidInteractWithDoor.java`，保留原包与 MIT 来源
- 原 `MaidBrain` 将门任务放在 priority 2、MoveSink 启动前；仅在 PATH 已开始且未结束时执行，保留相同 next 节点置 3、不同节点递减的原顺序
- 原 prev 门即使已开也记忆，next 门仅新开时记忆；关闭保留原节点/脚位置判断、方块中心严格 8 格边界、缓存附近同类实体严格 2 格及其 PATH 前后节点让门条件
- 原木门关闭后移除记忆，栅栏门本次关闭后保留到下次观察；DOORS_TO_CLOSE 没有 codec，行为刷新重建任务但保留集合，不新增 NBT
- 自有 `MaidDoorActor`、`MaidDoorAccess` 适配帧授权、原 PATH 记忆和目标平台
  实读 GTNH 2.9.0 的 MalisisDoors `1.19.13-GTNH` 门、栅栏门与 TileEntity 字节码及 modifyVanillaDoors 配置
- 木门通过原生开关入口保留锁定规则，动画栅栏门通过自身 TileEntity toggle；原生 OPENING 期间可能不接受关闭，适配层保留待关记录等待后续原任务，不强改动画状态
- 现代分支关闭原型 Traversal 的门处理，旧原型入口保留；当前自然服务端证据不替代客户端动画、所有门变体、多人或完整 F/N 验收

## 未保留内容的调度影响与骑乘朝向

- Indev115 实读同一固定上游 MaidBrain、MaidCheckRateTask、MaidFindHomeMealTask、MaidJoyTask、MaidHomeMealTask
- 首版无野餐垫与游乐设施，自有 `MaidAbsentContentChecks` 保留空查找之前的计数和随机数消耗，不注册未请求内容
- IDLE 的 priority 6/7 分别按 WALK_TARGET 与 TARGET_POS 都不存在的前提推进两个独立 10+nextInt(10) 计数
  RIDE_IDLE priority 5 的用餐检查无记忆前提，推进独立 50+nextInt(50) 计数；刷新与取消重置计数
- 原 canAdd 为只读冷却查询；没有相应原方块、TileEntity 与座椅类型时，三个任务不启动，也没有后续随机数或有效记忆写入
- 实读原 EntityMaid.rideTick 及生成的 maid_vehicle_rotate_blocklist：在 super 之后对齐载具头/身体朝向
  两个可选排除 ID 为 Create 的 carriage_contraption 与 seat
- 目标平台使用原生 updateRidden 生命周期，先执行 super，再写 rotationYawHead/renderYawOffset；保留两个精确注册 ID 排除，不按模组名扩大排除
- 此项服务端朝向检查不代表客户端骑乘姿态与装备渲染已验收；原箭飞行基线、复杂水域与保留活动自然对照仍是完整 F 的准入项

## 原弓箭飞行数值与独立对照箭

- Indev116 实读固定 TLM TaskBowAttack.performRangedAttack/getArrow 与 MaidShootTargetTask 的 charge 传递
  依据实际 1.20.1 映射 Projectile.shoot、Vec3.normalize、RandomSource.triangle 和 AbstractArrow 的构造/附魔伤害方法适配基础箭
- 自有 SourceBowShot 保留 float 距离速度/散布、1e-4 归一化边界、先归一化加三个 triangle 再乘速度及伤害随机数顺序
- MaidGravityFreeArrow 使用原眼部发射点与方向，保留目标原生碰撞/拾取/NBT；原生已执行飞行分支后，撤回实装重力并调整为原水阻力
  此平台处理有浮点舍入差异，实际空气/水中检查使用位置 1e-10、速度 1e-12 容差，不声称完整弹丸生命周期逐位相同
- 实读 Galacticraft 3.4.37-GTNH 的 EntityArrowMixin 与 WorldUtil.getArrowGravity：该只读查询在其维度返回 .005F，其余 .05F
  最终仅用 MixinArrowFlightState 只读原生 ticksInAir；没有重力常量注入、局部槽位依赖或其他箭类型的行为改写
- 显式开发 profile SOURCE_ORIGINAL 不使用原生预瞄 solver/safety 插入；默认仍为 NATIVE_STATIC_COMPENSATION
  基础箭保留手优先、有限/Infinity 资源、原拾取标记、Power/Punch/Flame 和好感基础攻击倍率
- 共享生成事件后的授权/资源重核作为目标平台安全适配保留；不声明与上游取消事件的资源时序完全相同，也不支持所有现代自定义箭接口
- tools/source-bow-reference 在实际 Minecraft 数学类上生成七组记录，包含实际加载类 SHA，SourceBowShotTest 逐位重放
  这只证明列出的数值输入，不替代原实体、自然 O/F/N、同物理预瞄收益、客户端或最终验收
- 骑乘可选类型排除改走已有 MaidCombatEntities 的规范模组实体 ID，避免原生 EntityList 字符串格式差异

## 默认移动属性与自然跟随观察

- Indev117 实读固定 TLM EntityMaid.createAttributes；该构建器使用 LivingEntity 的基础属性，不另设移动默认值
  实际映射 Attributes 的 MOVEMENT_SPEED 默认是 `0.699999988079071`，与原版自然采集的 base/value 一致
- 目标新实体以 `(double).7F` 对齐该默认值，移除早期原型的 .3；原属性 NBT 读取保持，未批量覆写旧存档或自定义属性
- ModernFollowCoreComponent.cancel 重建门 OneShot 局部状态，关门集合仍按已有取消/刷新语义处理，不追加世界开关门操作
- NaturalFollowPortTests 在实际世界 tick 的 START 施加相同主人输入、END 观察全新实体，保留活动、传感器与 RNG；不手动 tick Brain
- 三场景各三次共 1800 帧，平台外部方块保存并恢复，原全局设置、在线合成主人、内存通道与区块票据随夹具清理
- compare_port 使用历史原版冻结工具核对原身份、输入和清理，再检查移植输入/年龄/属性/实体移除及 HQA 结果，输出组内/组间描述性轨迹
  原版真实连接主人与移植标准 FakePlayer 分开标识；此观察不准入完整 F、统计收益、全部场景或执行成本结论

## 外部依赖与库存失效保护

- 新增 `common.protection` 为本工程独立实现，不从其他模组复制通用修补器或自动迁移未知 API
- EntityMaid 保留原实体包名，在已有 `writeEntityToNBT` / `readEntityFromNBT` 接入物品身份、无损封存及越界保管
  原生 Equipment 读取前移除待解码列表，由本模组边界逐项恢复，避免原生 null 结果吞掉未知物品
- MaidShieldItems 的 Battlegear 类型引用与 MaidFoodProfile 的 AppleCore 引用隔离到按需内部适配器
  可选依赖不再用本模组硬启动依赖阻止存档保护执行；不表示整个 GTNH 包的其他模组也能缺少这些依赖启动
- 对照实装 Forge 的 MapStorage.loadData：其异常后可能返回已构造但未完成读取的对象，因此检查完成状态，拒绝覆盖既有不可读基线
- 重力箭模组数字 ID 调整为 2，墓碑沿用 1，实体存档名称保持原值；修复此前两者同时使用 1 的网络注册冲突
- ProtectionTests 的类缺失验证仅隔离载入本模组适配器，不物理移除实际整包依赖，也不冒充修改过的完整 GTNH 重启验收

## 原生数值持久化边界

- 实读目标 Forge ItemStack：writeToNBT 将 stackSize 转为 byte、itemDamage 转为 short；readFromNBT 将负 itemDamage 改为零
  getItemDamage 转调 Item.getDamage，不能在保护快照中当成原始字段读取
- schema 2 另存完整整数、核对原生投影；写出深复制，槽位接收不再直接 Math.min 裁剪超限数量
  读取原始字段使用已知名称的只读反射，不新增对 ItemStack 的强制 Mixin 注入或未知字段修复
- Hodgepodge 2.7.221 实装 MixinMapStorage_threadedIO 将存档提交 WorldDataSaverHook，再进入原生 ThreadedFileIOBase 队列
  跨进程夹具等待磁盘完整 NBT 与预期相同，不能把 saveAllData 返回当作落盘完成，也不修改全局保存配置
- 新增测试提供方 Canary 仅属于非发布 pilot 模组，保持 mod version 1；变体只修改其类中 API 方法名称，原调用方保持旧方法引用
  此范围用于验证真实提供方代码摘要变化与缺失方法回退，不代表全部 GTNH 第三方升级组合已验证

## 客户端依赖边界与逻辑端隔离

- Indev120 的客户端基线、渲染预检、逻辑端故障记录及开发探针为本工程独立实现
- 保留上游 `client/gui/entity/cache/CacheIconManager.java` 的原包名，改动仅为捕获安全回退的空纹理并停止该轮队列，不将缺失结果放入缓存
- 平台适配读取实际 TextureUtil 字段及 Angelica GLSM 的方法、上下文与字段类型；按需初始化，API 缺失不转成假的矩阵深度
- CompatibilityLedger 区分已构造的新基线与 MapStorage 未读完的对象；新建实例完成检查后标记可用，损坏的既有文件不替换
- ProtectionTests 使用既有 Hodgepodge 异步落盘观察方式，等待完整 NBT，再核同一缓存对象的再次验证；不更改全局保存设置

## 迈步高度默认值

- 固定原版 EntityMaid 的 TYPE 声明尺寸为 0.6/1.5，构造器未单独设置迈步高度；真实原版自然采样的出生与 1800 帧前后状态均记录 stepHeight 为 0.6
- 目标反编译 EntityLivingBase 构造将 stepHeight 设为 0.5；本工程保留 EntityMaid 原包名，在构造器显式采用原版观察值 0.6
- MaidStepPhysicsTests 不推进 Brain 或手写移动公式，直接用实际 Entity.moveEntity 和 BlockBed 的 0.5625 碰撞高度验证差异；此证据仅覆盖所列碰撞边界

## 原版水域观察器

- 开发单元122继续使用固定原版 checkout 与已审查的独立 probe 注入，不修改原版女仆/Brain/导航运行逻辑
- 原版 MaidFollowOwnerTask.start 按活动半径减2开始跟随，再加4决定常规远距传送；低通道的大位移仅作为观察保留，不从END位置反推实际传送调用
- Platform 仅在预检为原始空气的区域放置水源和玻璃，核真实方块状态与摘要后按水源、外壁顺序清理；原平台依旧独立核4225个方块恢复
- 身份集合补入原水下导航、节点评估、boarding路径、游泳管理/跳跃与Minecraft两栖类，实际路径节点只读取已生成的type和costMalus，不触发额外搜索
- 历史平坦合同、旧水域失败采集和新水域有效采集分别保留；改后的解析检查器通过配对冻结身份分层核证据，不宣称其重新生成了原采集构建身份

## 水域移植侧观察与清理所有权

- 开发单元123只改独立QA/比较工具，生产候选仍121；原版水域合同按原字节打包，替换早期平坦轨迹的硬编码输入
- 原版平台feet位于origin.y，移植夹具floor位于fixture.y，因此仅按明确feet偏移加1布置；不归一化实际速度、运动或属性差异
- HQA实际清理栈及ModernCoreTests.Fixture的模板清除确认子清理需提前登记，prepared标识防止未完成子构造时触发清理
- 新观察读取实际MaidModernPath的节点type/cost，不额外调用寻路；原版配对冻结工具与原身份保持，比较器的后续变化不冒充原采集器重编译
- END样本缺少移植首帧前状态，其位移保持未观测；原版首帧使用真实before/after，不因按END相邻帧比较而漏记初始大位移

## 原版跳水的Brain相位

- 固定上游MaidBrain.registerCoreGoals将MaidSwimJumpTask置CORE优先级0首项；其继承Swim的启动/运行生命周期，原版附加水下和导航结束条件
- 保留原MaidSwimJumpTask包名，适配器拆tryStart/tickOrStop，旧tick仅顺序调用两者；现代组件按整个Brain启动/运行pass分别调用
- MaidAquaticController.beforeLivingUpdate不再提前运行现代组件的被动跳水，旧原型入口保持；刷新及取消清现代任务待发请求
- 实读目标EntityLivingBase.onUpdate：Forge LivingUpdate事件早于super.onUpdate及流体刷新，不能据该事件冒充Brain前已刷新流体的观察点
- 实装HQA GameTestAssertException字节码继承AssertionError，探针改用既有AfterNavigationActions真实回调，并把断言等非致命失败交回HQA

## AI 世界入口的失效回退

- 保留上游 EntityMaid 原包名；根据实读目标 EntityLiving.updateAITasks 的可覆盖入口，将原生导航/感知及其女仆回调置于单实例保护中
- MaidBehaviorProtection、按 UUID 的故障范围、物主受众与策略失败退休为本工程独立实现；不以快照补发撤销外部回调的任意副作用
- EntityMaid 原生物理继续运行，水域管理前后阶段与 Brain.afterLivingUpdate 使用同一边界，避免存档读取后从旁路重新运行旧原型行为
- MaidPolicyRuntime 原回调仍抛出主异常，世界入口接住可恢复错误；已失败清理不重复，原错误与 suppressed 清理证据保持

## 独立模组适配分类

- Indev126 将本工程适配与专用开发夹具移入 `com.tianluoqaq.tlmnh.compat`，按模组及侧/用途、职责继续分类，不搬入第三方模组源码
- 原 NativeDoorState、MaidDoorAccess、MaidVehiclePlatform、NativeArrowGravity 与渲染平台状态改包，提供方接口和已有安全回退保持；反射绑定、纹理/批次查询与工具构造按实际提供方抽取
- 保留上游 MaidModelDetailsGui 包名，盾牌预览只改为经 Battlegear 适配查询原有木盾物品
- 16处搬移及抽取的关键源码 token 相同，公开访问只为原调用跨包；源码比较不代替实际运行证据

## 原版保留 Brain 的专用 F 绑定

- 实核固定上游 `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6` 的 MaidBrain CORE/活动与 TaskIdle、TaskAttack、TaskBowAttack 普通/骑乘注册；范围继续遵循首版保留内容
- 本工程 ModernFollowCoreComponent 改为 MaidFaithfulBrain，新增 FaithfulActions 绑定标识；去除类型名/标识变化后，任务体源码 token 与改名前一致
- F 工厂、实体保护选择、世界/UUID 绑定及 NBT 后重建为本工程独立生命周期实现，不把旧原型作为 F 的缺失能力回退
- 保留上游 EntityMaid 包名，仅增加经既有实体保护的显式开发选择入口；上游任务文件的原包、来源和既有合同继续保留

## 独立增强核心切片

- EnhancedCoreBrain为本工程独立决策实现，不包装或调用MaidFaithfulBrain、旧原型root
- 复用已有移植的呼吸动作、移动sink、平台actor和感知；原移植来源及各自合同保持，不宣称增强策略属于上游原版
- 平台actor只新增跨职责的感知绑定可见性，F任务体与原随机调用顺序未改
- 协调器按请求类型创建独立实例，增强切片与F共用既有受保护生命周期；专用切片标识不能取得完整N资格

## 增强导航预算与明确查询结果

- EnhancedNavigationController与MaidPathCreation为本工程独立实现，不调用F的MoveToTargetSink调度
- MaidPathNavigator只在实际增强profile的查询边界取得世界额度，覆盖root、呼吸预检和导航模式切换，F的原nullable查询与随机调用合同保持
- 平台actor新增增强专用同步结果入口，区分没有导航条件、预算等待、实际路径和无路径；实例仍受既有世界/身份/授权保护
- 初包与最终包只增强控制器及编译辅助类不同；MaidFaithfulBrain、FollowCoreMoveDriver及移植MaidNavigationManager的字节相同，旧F运行证据不改写为最终包新执行

## 增强家园与生活决策

- EnhancedLifeController为本工程独立实现，复用只读MaidLifeSensor、共享床租约与已有受保护蛋糕执行，不调用F或Legacy root
- 增强家园/生活合法性、分组选择、候选重查与租约生命周期在独立组件内完成；家园活动变化通过协调器原有失效边界处理
- 共享MaidModernLife允许实际持有床租约的同一女仆完成入睡，其他占用者仍拒绝；固定上游任务的原包与来源保持
- 同块更换床TE以实际对象身份重新判断；替换后同tick合法重新入睡无需人工等待帧
- 初/最终131玩法包只增强生活类字节变化，F自然睡眠44tick与租约CPU按其原执行层复用，不改写为最终包新执行

## 增强战斗与原生移动靶解算

- EnhancedCombatController为本工程独立实现，目标选择/分组与近战、弓、盾动作组合不调用F或Legacy root
- 复用固定上游TaskAttack/TaskBowAttack的任务合法性及MaidUseShieldTask/MaidAttackStrafingTask动作原语；原文件包名、来源和执行服务保持
- ModernFollowCoreActor新增本帧目标视图、回调提交许可与仅自有弓租约释放，F旧入口默认许可和原生静止靶profile保持
- 增强actor显式使用已有ObservedTargetMotion估计与原生物理求解器，未移植新的预测框架或更改原生箭重力
- 目标事件的实际重定向结果、伤害Pre和原生箭生成回调后重新判断合法性；隐藏追踪只使用最后可见点

## 独立增强panic

- EnhancedPanicController为本工程独立实现，使用实际接受伤害的40tick临时记录和当前可见的苦力怕候选，不调用F活动状态机或Legacy panic控制器
- 可见威胁重查实际世界、维度、实体登记、距离与视线；纯受伤事实不携带可供逃离使用的攻击者位置
- 复用现有actor的原生坐姿/下乘骑、睡眠释放、陆地随机采样和位置行走入口，路径仍由增强导航的世界A*预算处理
- 呼吸、危险、战斗、指令与普通动作依次硬分组，避免同组滞回让panic挡住新出现的低氧；F原活动优先级保持

## 独立增强乘坐与进食仲裁

- EnhancedRideController为本工程独立决策，复用既有MaidVehicleActor、当前主人视线、实际坐姿/座位与物品执行入口，不调用原乘船任务调度
- 外部EFR船/座位反射和容量规则仍集中在compat/etfuturum/common/entity，新增增强类不直接加载外部模组类型
- EnhancedCombatController在乘坐时保留范围内近战与原生弓执行，抑制地面追击/横移；伤害及箭生成回调后的实际乘坐身份纳入提交许可
- 增强root补普通工作餐及乘坐变化的自有资源释放，原Meal控制器的开始效果、有限库存、手部交换和正常完成结算保持
- ModernFollowCoreActor只增加当前主人视线、诱惑物与控制载具重验入口，F原方法和原活动调度保持

## 增强世界感知配额

- MaidSensingBudget、MaidWorldSensingBudget与MaidSensingSession为本工程独立实现，世界线程FIFO只保存女仆/实例/类型身份，不保存世界回调或目标动作
- MaidModernPerception新增显式可选准入入口，增强绑定世界配额及60tick观察有效期；F默认构造保持原20/30tick Sensor节奏与可见缓存
- 增强生活控制器在实际MaidLifeSensor扫描前取得配额，缺额保留尚合法候选；持有床的当前对象检查与续租继续走原受保护平台
- 固定上游MaidBreathAirTask在实际找空气前增加默认允许的准入检查，F默认true；增强缺额直接返回等待，原BFS/空气判断/路径和物品执行保持
- 世界卸载同时移除感知配额，增强生命周期取消按构造时实例scope清队，不返还已使用额度；不改变原A*配额

## 增强缺氧和复杂移动恢复

- EnhancedBreathingController为本工程独立实现，负责低氧离座、空气候选重试和预算等待；复用固定上游MaidBreathAirStopTask停止动作、平台BFS和既有物品生命周期
- EnhancedNavigationController新增同帧预检交接、无推进检测与局部路径退避；实际路径创建/安装继续通过原平台身份检查和世界配额，不修改F的MoveToTargetSink
- EnhancedClimbController独立持有增强攀爬生命周期，复用固定上游MaidClimbTask动作原语；上游原包、许可证及动作数值保持
- 增强root取消空气请求与本实例候选，旧权限清理仍由ModernFollowCoreActor按自有资源执行，不释放其他动作持有的手部租约
