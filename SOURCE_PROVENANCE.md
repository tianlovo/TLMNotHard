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
