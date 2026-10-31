# 开发与验证

当前为 indev，构建通过和入口加载不代表女仆玩法可用，模型、完整交互、行为和原版 benchmark 仍在后续计划中

## 构建

JDK 25 启动 Gradle，Jabel 使用 JDK 17 / release 8，运行游戏使用 Java 25；本地需要安装相应工具链，不能将 forceToolchainVersion 改成 25 绕过 Java 8 输出要求

```powershell
./gradlew.bat spotlessApply build
python scripts/verify_bytecode.py build/libs/<候选版本>.jar
```

第一次构建会下载 Forge、MCP 和 Minecraft 原版开发依赖，网络失败应核对下载与缓存，不能关闭格式和字节码检查

## IntelliJ IDEA 本机配置

使用 IntelliJ IDEA 2026.2.1 打开源码仓库根目录，也就是 README.md、build.gradle.kts 和 src/ 所在目录；不要打开外层研究工作区或上游克隆目录，如果出现导入选项，选择 Gradle

项目 SDK 和 Gradle JVM 选择本机 JDK25，源码语言级别为17，IDE 导入模型与实际构建的目标字节码均为8；Jabel 编译器仍使用 JDK17，单元测试与 RC2 游戏运行于 Java25，
构建和测试委托给 Gradle，不使用 IDEA 原生编译器绕过 Jabel

使用项目 Wrapper 固定的 Gradle 9.7.1，Gradle User Home 选择已有项目缓存；发行包缓存在该目录的 wrapper/dists，依赖缓存与发行包缓存需要分别齐备；
已有同版本本机安装时可以校验后复用，避免 IDEA 导入时重新下载；本机目录、SDK 名称与解释器路径只保存在忽略的 IDE 配置或仓库外层工具中，不写入公开仓库

本机运行配置生成器位于仓库外层的 scripts/configure_idea.py，用 Python3 执行；它保留原设置备份，生成 .idea/ 下的项目 SDK、编译目标、Gradle
关联、本机任务及八个运行入口，并设置 IDEA 的本工程缓存位置；先关闭 IDEA 再重新生成，避免运行中的 IDE 覆盖配置；若 Wrapper 缓存缺失，先运行外层
scripts/seed_wrapper_cache.py 复用已验证的本机安装；.idea、.run、.vscode、.fleet、Eclipse 和模块文件继续由 .gitignore 忽略，不提交到远程

打开项目后等待 Gradle 同步与索引；需要手动同步时，在 Gradle 工具窗口执行 Reload All Gradle Projects

SDK 与 Gradle JVM 的设置位置见 [JetBrains 的 Gradle JVM
说明](https://www.jetbrains.com/help/idea/gradle-jvm-selection.html)，缓存目录和构建委托见 [Gradle
设置说明](https://www.jetbrains.com/help/idea/gradle-settings.html)

| 本机运行入口 | 使用方式 |
| --- | --- |
| 00 准备 RC2 测试包（两端停止时） | 执行完整 build 和字节码检查，将同一候选安装到专用双端；版本为显式 0.1.0-indev.idea，不创建 tag |
| 01 RC2 测试服务端 | 用 Java25 启动专用测试服，启动器接管后 Gradle 任务结束，游戏继续运行 |
| 02 RC2 测试客户端（主菜单） | 直接启动专用客户端到主菜单，不调用 Prism |
| 03 RC2 测试客户端（加入本地服） | 启动专用客户端并连接127.0.0.1:25585，先等测试服日志出现 Done |
| 04 RC2 正常停服并保存 | 发送 save-all 和 stop，等待正常退出，不强制终止 Java |
| 05 RC2 测试实例状态 | 查看双端 PID、退出状态、日志路径和候选 SHA-256 |
| 06 单元测试（Java25） | 执行 Gradle test |
| 07 构建与完整检查 | 执行 Gradle build，含格式、规范和两版 Gson 检查，不安装或发布 |

先运行00，再运行01和02或03；改代码后先让客户端从游戏菜单退出，再用04正常停服，最后重新运行00；准备包与启动分开，避免另一端仍运行时替换 JAR

运行01–03后，任务成功结束表示启动器已经接管，不表示游戏退出或完成验收；用05查实际状态，日志位于专用实例各侧 console-时间戳.log，路径会打印到运行窗口；不要用 Gradle 的红色停止按钮代替停服

这些入口复用外层 scripts/idea_runtime.py、test_runtime.py 和 install_candidate.py，只允许操作 instance/test/tlmnh；
测试整包、存档、日志和本机外层工具不进入源码仓库，其他开发者需自行准备相应测试实例，普通 Gradle 构建不依赖这些本地运行配置

## README 维护规范

README.md 默认显示简体中文，README.en.md 为英文，两者在页首互相链接；当前是 Indev 说明，已实现、已检查和待验收须分开写，不能提前使用正式版安装指南或可用性承诺

章节顺序参考 [Standard Readme](https://github.com/RichardLitt/standard-readme/blob/main/spec.md)

标题与简述、目录、背景、安装及依赖、使用、开发状态、维护者、参与贡献、许可证；标题可以翻译，许可证放最后
多语言文件名按本项目中文首页要求处理，属于 Standard Readme 默认英文文件命名之外的项目约定

语法使用 [GFM](https://github.github.com/gfm/)，格式由
[markdownlint-cli2](https://github.com/DavidAnson/markdownlint-cli2) 检查，固定工具版本0.24.0，配置在
.markdownlint-cli2.jsonc；当前检查两份 README，普通文本每行最多120字符，表格不限制行长，其余默认规则开启

GitHub 工作流只在两份 README、markdownlint 配置、工作流自身或路径检查器变更时自动运行，支持手动执行；同一分支或 PR 有更新时取消尚未结束的旧检查，纯源码提交不触发文档检查；工作流仍检查
Git 跟踪的文本是否混入本机绝对路径，每次推送前也须在本地执行路径检查

```powershell
npx --yes markdownlint-cli2@0.24.0
python scripts/check_repository_paths.py
```

更新进度时同时校对双语版本、目录锚点和相对链接；新增中文按 humanizer-zh 校订，保留未完成与未验证的范围；原创代码许可尚未确定，不能把上游 MIT 与资产 CC BY-NC-SA 4.0 合并成整个项目的 MIT

正式版发布前，另行改写对应玩法、获取方式、依赖、安装更新、已知限制和验收结果；本轮不把 Indev 标记移除

## 代码职责

| 路径 | 职责 |
| --- | --- |
| com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid | 原包名实体、DataWatcher、属性与核心 NBT |
| com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule | 上游 DAY、NIGHT、ALL |
| com.tianluoqaq.tlmnh.common.inventory.MaidInventory | 六格背包与装备槽位视图，没有第二份主手或护甲数据 |
| com.tianluoqaq.tlmnh.common.inventory.ContainerMaid | 容器槽位、物品转移和主人操作的服务端复核 |
| com.tianluoqaq.tlmnh.common.registry.ModEntities | 模组内实体 ID 0，持久化名称 tlmnh.maid |
| com.tianluoqaq.tlmnh.common.command.DevelopmentCommand | 显式开发开关下的 Forge 环境检查 |

实体同步索引 20–27 分别为模型、任务、作息、经验、饥饿、好感、拾取标记和副手，28 为短时动作标记；主手与护甲沿用原版装备同步，副手独立保存并在数量、耐久或 NBT 原地变化后同步副本

背包视图 0–5 为背包，6 主手、7 副手、8–11 为靴子到头盔；只有六格背包计入基础容量，不能把 12 个总槽位称为十二格背包

存档扩展使用 TLMNH.Schema = 1，背包为 MaidInventory 列表，副手为 TLMNHOffhand，原有模型、任务、作息和经验等键保留；TLMNH 扩展区中的未知字段保留，不宣称支持读取高版本
Minecraft 存档

## 真实服务端状态检查

在专用测试服务器的 JVM 参数中显式加入 `-Dtlmnh.devCommands=true`，默认不注册此命令；控制台或权限等级 4 的操作者可执行：

```text
tlmnhdev state-test
```

它构造不加入世界的临时实体，检查首次绑定、拒绝重复绑定、全部 12 个槽位、名称、生命、坐姿、任务、作息、生活数值和拾取开关的 NBT 往返，并检查副手原地变化和损坏枚举的回退；成功标记为 TLMNH_STATE_TEST_PASS

该检查不会给玩家发物品，也没有验证区块保存、服务端重启、容器交易或客户端观察者；后续必须分别验证这些流程，不能将本标记当作完整 M1 或首版验收

区块保存与服务端重启使用独立夹具，只在专用测试存档执行：

```text
tlmnhdev persist-create
save-all
stop
```

重启同一存档、同一候选 JAR 的服务器后执行 `tlmnhdev persist-check`，检查带原 UUID 的唯一女仆、12 个槽位、装备耐久与 NBT、核心状态和扩展区；期望值保存在独立的
WorldSavedData，夹具实体按普通区块流程保存

成功标记为 TLMNH_PERSISTENCE_CHECK_PASS；测试报告同时保存两次 Java 进程、停服和启动日志，单次进程内执行 create/check 不能作为重启证据；夹具无敌且静坐，不能用于战斗或生存玩法验收

容器夹具使用 `tlmnhdev container-test`，在真实服务端构造两名临时 FakePlayer 和一只临时女仆，检查堆叠与满格守恒、护甲有效性、非主人拒绝、距离与实体身份撤权，以及设置操作的逐次校验；
结束时清理夹具物品和实体

成功标记为 TLMNH_CONTAINER_TEST_PASS，不表示已经验收客户端界面、实际网络点击或多人观察；按钮复用原版 C11 容器消息，动作编号限制在 0–8，服务端不会信任客户端的槽位、所有权或距离判断

## 主人界面与基础交互

野生女仆使用蛋糕首次绑定，生存消耗一个蛋糕，创造不消耗；已绑定女仆不接受其他玩家的蛋糕，交互不提供换主人入口

主人普通右键打开六格背包、双手和护甲界面，可以设置任务、作息、拾取与坐姿；潜行空手右键切换坐姿，坐下时清除当前目标与路径
模型选择、核心交互和部分自然行为已有接入，具体实现与实测范围见 [Indev 进度](PROGRESS.md)；切换战斗任务不表示完整战斗与行为 AI 已验收

CommonProxy 创建服务端容器，其初始化入口不引用客户端类型；ClientProxy 注册 GUI、女仆渲染器和资源重载监听；开发开关开启时，`tlmnhdev spawn [玩家] [模型ID]`
在在线玩家附近生成一只绑定测试女仆，指定模型必须存在于内置目录，否则拒绝生成

`tlmnhdev interaction-test` 使用未加入在线列表的服务器玩家检查蛋糕消耗、首次绑定、拒绝换主人、坐姿和超距拒绝，成功标记为 TLMNH_INTERACTION_TEST_PASS；
它不发送真实玩家网络点击，也不打开客户端界面

## 客户端测试控制

### 虚空场地与旁观观察者

开发开关下注册独立空气维度，默认ID7337，可通过两端相同的 `-Dtlmnh.labDimension=<ID>` 调整，冲突时停止注册；生成器不生成地面、结构或装饰，仅场地初始化创建21×21格网平台，
使用独立存档子目录；固定正午、关闭局部天气和自然刷怪，不写主世界的时间与天气数据

服务端另加 `-Dtlmnh.animationLabAutoEnter=true` 时，在登录事件中自动切换到RC2实装的旁观模式并进入展台；默认模型为汉服酒狐，可用
`-Dtlmnh.labModel=<内置模型ID>` 指定；进入前记录返回位置和原模式，复用同一只带展台标记的女仆，不改背包内容；鼠标光标上仍拿着物品时拒绝进入，避免触发旁观模式的光标物品处理

```text
tlmnhdev lab-world-test
tlmnhdev lab-enter [玩家] [模型ID]
tlmnhdev lab-observer-test 玩家
tlmnhdev lab-exit [玩家]
```

第一项在真实服务端检查平台外四根完整空气柱、441格平台、固定时间，并实际尝试生成史莱姆，要求生成被拒绝；观察者检查另行要求实际模式ID3、无敌、飞行、存活和场地女仆；生成边界只允许玩家和带展台标记的女仆，保护仅作用于此开发维度

本机测试启动器默认开启自动旁观展台，并在环境与旁观预检通过前拒绝自动启动观察客户端；普通玩法与AI测评使用单独准备的交互场景，不能把旁观展台当作行为测试；当前只建立场地与真实实体播放，指定动作循环、暂停、逐帧及场景元数据仍待接入

### 请求与结果

客户端同样需要显式加入 `-Dtlmnh.devCommands=true`；启动后在游戏目录的 tlmnh-dev/control/ 读取 JSON 文件，在 tlmnh-dev/responses/ 写入同名结果；
默认关闭此入口，不通过键鼠或 IDE 界面自动化驱动游戏

写入请求时先使用 .tmp 后缀，内容完整后改成 .json；文件按名称排序，每 tick 最多处理8个，单文件不超过16KiB；已处理请求改为 .json.done，结果的 status 为 complete 或
failed，截图先返回 pending-render，实际绘制结束后再更新结果

| 请求 JSON | 作用 |
| --- | --- |
| `{"command":"status"}` | 查询世界、玩家、当前界面及窗口尺寸 |
| `{"command":"connect_local","port":25585}` | 从主菜单调用 Forge 常规连接入口加入本地测试服，只接受127.0.0.1和1024至65535的整数端口 |
| `{"command":"lab_view"}` | 仅限安全旁观者所在的虚空展台，关闭游戏菜单并临时关闭失焦暂停，以便后台截图 |
| `{"command":"look","yaw":180,"pitch":10}` | 设置当前玩家相机方向，yaw 限定为−360至360，pitch 为−90至90 |
| `{"command":"screenshot","name":"winefox-idle-01.png"}` | 在下一次 RenderTick.END 用原版截图接口保存游戏画面，结果记录相对路径 |
| `{"command":"reload"}` | 在游戏主线程重新加载资源 |
| `{"command":"shutdown"}` | 调用 Minecraft 正常退出流程 |

截图仅接受受限 ASCII 文件名和小写 .png 后缀，不能带目录或覆盖已有文件；有界面遮挡时拒绝截图，入队和实际截图时分别检查；队列最多8张，读取后恢复截图接口改动的纹理绑定与像素对齐状态；请求不支持任意代码、聊天或服务端命令

lab_view不保存游戏选项，离开展台或通过shutdown退出时恢复此前的失焦暂停设置；它只接受已关闭界面或游戏菜单，拒绝关闭容器及其他界面，其他维度不能使用

connect_local 返回 complete 仅表示已请求连接，仍须用 status 核对 worldLoaded、playerPresent 与实际维度；已加载世界时拒绝再次连接，不自动断开现有会话；
本地启动器的自动加入入口在客户端开始运行后发送该请求，不再传递 Minecraft 的 --server 参数

这套入口用于专用测试实例的实际渲染检查；协议单测不能证明 GL 截图可用，普通世界截图也不能代替虚空展台、完整动作循环或多人同步验收；生成的请求、结果与图片不提交到源码仓库

### 持物 GPU 诊断

`held_item_gpu_probe` 仅用于关闭光影的安全虚空展台，当前接受灵梦、基础 Gecko 酒狐与已审查的物品夹具
请求可指定四个固定视角；每个视角单独取样，不代表跨视角保持同一姿态

```json
{
  "command": "held_item_gpu_probe",
  "model": "geckolib:winefox",
  "action": "main_bow",
  "view": "negative_z",
  "warmupTicks": 30
}
```

`warmupTicks` 为 0 至 60 的整数，省略时为 0；0 表示即时取样，不能用来判断动画后续阶段是否可用
正值要求女仆与客户端确实经过相应 tick，且持续装备期间有自然完成的模型绘制，再运行同姿态的本体合成与单独物品诊断
动画时间只读，不手动推进、回绕或修改骨骼缩放；控制器时间的派生值不等于已证明动画稳定

本次临时装备由独立事务持有，完成、取消、世界卸载及正常退出共用清理路径
只有物品引用与完整值仍属于该事务时才恢复原装备；外部更新会使事务失败并保留外部值，不反复覆盖它
物品工厂或恢复操作内重入的取消先排队，临界操作退出后统一处理，终端事务不能继续安装装备
总请求仍受 15 秒和 200 次客户端 tick 限制，单次 GPU 查询最多等待 500 毫秒，超时保留失败

开发开关下另加 `-Dtlmnh.heldCancelFactoryOnce=true`，可在首次盾牌夹具的真实 `ToolBuildEvent` 内触发一次取消
它不取消 Forge 事件；结果延迟到下一客户端 tick 再读取原女仆装备，后续同进程请求不再触发该故障

结果同时记录事务完整性与实际绘制数据，采集有效和合成可见分别判定
单独物品可见只用于诊断，不能代替原版附件位置、世界渲染、光影或全部模型验收

## 维护边界

普通功能提交不打 tag，只在完成小阶段或里程碑时创建标签；日常构建用源码提交号、显式开发版本与 JAR SHA-256 追踪

内置模型资源使用 `scripts/import_builtin_assets.py --upstream <固定 TLM 克隆路径>` 静态核对，显式添加 `--write` 才导入；
脚本要求固定提交且相关上游文件没有修改，保留原字节与原始作者说明，生成模型装饰 ID 和逐文件来源摘要，不导入椅子或 LLM 设置

使用 `python scripts/verify_builtin_assets.py [候选JAR]` 核对源码或打包后资源摘要及引用；`tlmnhdev model-test` 在服务端检查 254 个装饰 ID
的 NBT 往返、酒狐 19 个 Gecko 基础条目与 2 个 Bedrock 基础条目合计 35 个选择，以及未知模型回退；上述检查均不能替代客户端渲染和动画验收

`build` 包含 Java 25 上运行的 JUnit 几何测试，测试源码仍通过 Jabel 编译为 Java 8 字节码；可用
`./gradlew.bat test --tests '*BedrockGeometryTest'` 单独检查 Bedrock 几何，`LegacyPoseStackTest` 检查矩阵与法线变换

几何测试直接向内存顶点接收器输出，覆盖全部 205 份非 Gecko 内置几何，包含两款 Bedrock 酒狐；不会创建 GL 上下文，不能据此宣称游戏内渲染或 Gecko 动画完成；JOML 仅打包 Java
数学类并重定位，字节码检查仍扫描它们

`GeckoGeometryTest` 覆盖 26 份内置 Gecko 几何与独立骨骼姿态，检查酒狐 19 个基础条目和 33 个 Gecko 选择的几何输入、镜像、逐面 UV、隐藏控制、定位骨骼、快照及异常矩阵清理；
AnimatedGeoModel 保存每只女仆的可变姿态，关键帧、Molang 与核心状态控制器已接入，实际画面仍需验收

模板默认测试类路径使用 Gson 2.2.4，`testRc2ClientGson` 以 RC2 客户端的 Gson 2.10.1 再运行几何、模型、动画控制器与测试协议检查；两组测试都使用 Java 25，均由
`check` / `build` 执行，兼容性测试依赖不会打包进模组，也不会替换整包的 Gson

`MaidRenderer` 继承 RC2 的 RenderLiving，将同步的模型 ID、原贴图和实体缩放接入生物渲染；解析后的几何按资源缓存，可变姿态按女仆持有弱引用缓存，资源重载后两者失效；
模型资源损坏时记录错误并尝试默认模型，这种回退不能算作该模型验收通过

`BuiltinMaidModelsTest` 逐项创建并输出 254 个选择，包含酒狐 35 个选择，检查两种坐标的转换、姿态隔离、资源重载、流关闭与异常清理；Gecko 已使用独立时钟、核心片段选择和原顺序控制器，
按上游规则补齐缺失的默认女仆片段；选择界面与双手物品层已接入，完整动作组合、护甲层和实际画面验收仍待完成

Bedrock 渲染入口按资源中的顺序执行上游内置动画组合，每帧先恢复全部骨骼与整体变换；两个固定 JS 已逐式转为 Java，仍按原资产许可提供，不执行外部脚本；`BedrockPoseTest`
检查复位、嵌套绑定和异常清理，`BuiltinBedrockAnimationsTest` 让全部 213 个 Bedrock 选择各输出十种姿态输入，另查左右手、护甲、生命值与两个脚本的公式

`MaidPose` 的睡眠、乞食、用手、副手挥动和拉弓标记由服务端同步，动画侧读取一次绘制的快照，动作标记不写入存档；当前接入了原有坐姿、骑乘、摆动、生命和装备状态，
睡眠、偷吃和拉弓已接入服务端生活或战斗执行器，手动推进夹具已有实测；自然行为与完整画面另验，乞食和硬编码游泳等动作层仍待完成

Molang 表达式核心保留上游包名及语义，`MolangEngineTest` 检查解析、短路、作用域和上下文隔离；数学、帧查询、TLM 内嵌查询与控制器时间绑定每次绘制的上下文，各模型实例独立保存变量与物理状态；
时间轴指令可执行，声音与粒子事件暂未接入游戏播放

上游来源与许可见 [第三方说明](THIRD_PARTY_NOTICES.md) 和 [移植记录](SOURCE_PROVENANCE.md)，复制原文件时保留包名，独立适配文件使用 com.tianluoqaq.tlmnh

修改存档、槽位、实体 ID 或网络字段需要记录迁移方式；公共侧不得引用客户端渲染类，所有主人操作仍由服务端复核权限和当前状态

### 自然行为开发探针

启用开发开关的独立测试服提供 `tlmnhdev bow-runtime-test` 与 `tlmnhdev life-runtime-test`
测试只操作专用世界中预检为空气的隔离脚手架，使用内存 PLAY 主人，互斥运行；真实玩家登录、GUI网络和复杂地形不在此范围
女仆、原生箭与伤害由正常世界 tick 推进，探针只设置场景输入并记录观察，不手动调用行为控制器或实体更新
弓测试观察自行选敌、接近、静止和横移靶命中、友方阻挡及恢复、坐下中断和站起恢复
命中使用本轮原生箭伤源与目标实际生命变化共同判定；`getSuccessfulAttacks()` 仅表示攻击执行成功，弓箭发射不能当作命中率
生活测试保留原版等待时间，可能需要数分钟；日志中的 `STARTED` 不表示通过，只有清理成功后的 `PASS` 才是该轮结果
测试结果不证明完整行为智能或原版 benchmark，正式版前仍需自然场景、O/F/N与多人长期回归

### 行为记录与策略边界

自然场景命令可追加 `record` 或 `plain`，默认 `record`；后者关闭本轮记录，用于观察开关前后的结果对照
记录器只在显式开发命令中启用，普通女仆默认不采集历史；同一女仆的活跃会话互斥，事件、候选和目标数量有上限
完整记录写入测试服相对目录 `tlmnh-dev/episodes/`，日志保留计数、完整性标志和压缩文件摘要，不把大量事件逐条刷入控制台
文件写出、原始记录完整、自然场景通过和智能测评通过分别判定

`tlmnhdev telemetry-native-test` 用真实 Forge 伤害管道检查观测边界，
`tlmnhdev policy-lifecycle-test` 检查 UUID 恢复、卸载和策略失效；两项都是开发夹具，不代表自然 AI 已完成对照测评
伤害夹具使用实际登记到世界的原生牛，保留 Forge 伤害与原生账本管道，避免敌对生物入场时的随机精英词缀干扰该层检查
真实敌对选敌、原生箭碰撞和自然行为另用弓箭及生活场景验证，不把受控伤害方法调用当作这些能力的证据
受伤事件、成功发射、原生 CombatTracker 金额和生命净变化分开记录
原生账本金额可能包含过量伤害；裁剪后的金额只作估计，不冒充已读取的最终生命差
未绑定本会话成功发射的旧箭单列，不计入当前会话的攻击收益；治疗与多源伤害的 tick 净差不作精确伤害归属
发生漏采、未知账本重置或采集失败时保留原因，不能以剩余记录生成完整测评分数

默认调度继续使用 `LEGACY_PROTOTYPE`，保持已有战斗、生活、跟随调用顺序
`FOLLOW_CORE_COMPONENT` 是显式安装的开发组件，独占原生导航之后的预约阶段；未安装执行器时仍拒绝运行，不回落原型
该组件不保存到 NBT，不计入 benchmark 准入，也不表示完整 `FOLLOW_CORE_V1` 已通过
`FAITHFUL_TLM` 与 `ENHANCED` 尚未实现，选择后拒绝执行，不回退到旧原型冒充通过
策略 token 在保存数据恢复、卸载和身份变化时失效，旧 runtime 永久退役；重新注册实体需要实际世界成员关系
这些入口只提供后续 O/F/N 测评所需的隔离边界，不能据此声称已完成忠实基线或增强智能

### 原版跟随组件对照

`tools/follow-reference` 采集固定原版的真实任务组件，合同、身份和 A/A 检查见该目录 README
`prepareFollowReplay` 通过该目录的 `replay.init.gradle` 准备本地 Java25 重放入口，生成的机器路径仅在忽略的 build 目录
重放调用实际移植核心，缺字段即拒绝；它不包含完整 Brain 调度、MoveToTargetSink 或在线主人解析

开发命令 `tlmnhdev follow-teleport-replay <SHA256>.json` 只从测试服相对目录 `tlmnh-dev/follow-inputs/` 读取已核摘要的原版采集
它在预检为空的临时 STONE/air 场景实际运行 RC2 传送，结果写入 `tlmnh-dev/follow-results/`
日志中的 `CAPTURE` 只表示原始观察已保存，仍须独立比较随机调用、实际位置和原版结果；不以文件生成成功代替等价判定
原版组件、纯决策重放、RC2 实际传送和完整自然跟随分别判定，完整 F/N 模式仍未启用

### 移动 sink 与原生阶段

`FollowCoreMoveDriver` 按固定 MC1.20.1 的实际 `MoveToTargetSink`、`Behavior` 流程适配纯接口
保留合法记忆条件下的冷却、150..250 持续时间、严格截止时刻、整格 Manhattan 到达、目标位移平方大于 4 的重规划，
以及完整、部分、空路径与 fallback 的区别；原生导航、fallback 生成和卡住判定分别由适配边界提供
受控结果重放不能证明自然 fallback 或 RC2 卡住语义已经还原

备用落点由 `FollowFallbackSampler` 生成，按固定原版 `DefaultRandomPos`、`RandomPos` 与 `GoalUtils` 的调用顺序，
保留实体随机源、方向锥、十次尝试、择优时严格大于和方块底部中心坐标；生成阶段不查询或安装路径
角度计算保留原版查表与浮点步骤，光照评分保留原版 float 运算，不直接替换成旧版世界亮度
当前 RC2 适配仍限显式批准的明亮主世界石平面与完整空气体积；旧域默认拒绝 fallback，未知或未加载事实不会当作不可达结果
自然卡住判定、复杂地形和完整感知仍未接入，不能据新增生成器启用完整 F/N

开发命令 `tlmnhdev follow-fallback-test` 使用独立采样协议，记录一个合法空结果和一个明确可选落点，
随后分别检查生成阶段无路径副作用、实际原生创建/安装及取消清理
夹具使用未注册女仆、合成主人和手动 AI 阶段；不会把它记作自然主路径失败后的恢复或 O/F/N 测评
测试资源中的原版 utility 数据来自 Java 17 对实际 mapped 方法的独立调用，保留资源摘要，
只覆盖数值与择优规则，不宣称真实 Forge 运行或世界行为等价
RC2 的 BugTorch 会替换实体随机源，相同种子不能推导两代实现的候选相同
运行时对照使用实际 RC2 随机源委托给原版 utility，保留候选与随机消耗检查，不替换女仆的随机源

### 变更验证与正式验收

`navigation-stop-test` 开发命令验证原生导航停止反馈，使用 `tlmnhdev navigation-stop-test`
`MixinPathNavigate` 只观察 `pathFollow` 内 watchdog 清空调用的前后状态，不替换调用或修改 RC2 阈值
未被组件认领的导航器不分配观察状态；已认领路径的反馈须匹配引用、自安装代、token、世界和当前帧
同一原生路径正常结束不会因此视为停滞，原因未知的路径消失仍拒绝执行
识别到原生 watchdog 后，继续使用既有 MOVE 清理、随机冷却及 FOLLOW 重建流程
此原因标为 `RC2_NATIVE_WATCHDOG`，与现代导航的速度阈值和节点超时机制分开，不代表完整 F/N 已完成

首次启用 Mixin 需要验证目标类映射及双端启动；开发采样仍使用未注册女仆、合成主人和手动 AI 阶段
采样只保留关键边界，原生导航更新次数与实体阶段另行记录，不将其称作世界自然恢复或整包集成验收

每个逻辑完整的开发单元按实际行为风险选最低合理的 L0～L3 等级，优先复用已有证据，充分后继续功能开发
L0 通常只查差异与静态规则，不新增测试或启动游戏；L1 默认做局部检查
L2 通常选 1～3 个代表场景，L3 针对可信的高风险传播路径选约 3～5 个不同故障机制场景，数量不是配额
不能依据文件名、模块、行数或跨版本标签自动提高等级，也不默认跑全模型与全部动画的组合
失败后先定位实际路径，再重测失败项与必要关联项，不反复全量构建

上述等级用于每次代码变更，正式版发布前仍须独立执行跨模块集成验证
验收要检查各功能组合后的状态、物品与资源归属、客户端/专用服务端兼容和已列出的发布门槛
各单元分别通过，不能直接推导首版整体通过；当前状态仍见 [Indev 进度](PROGRESS.md)

`MaidBoundedPathPlanner.create` 只查询，`install` 才提交并核实际实体、世界、导航和 token 上下文
返回的三维终点关系只是原生事实，不是通用的现代 `canReach`；原 `request` 保留旧原型的立即安装语义

唯一协调器为开发组件预约，`EntityMaid.updateAITick` 保留父类调用后消费一次预约
这样将组件放在原生导航之后、移动控制之前；省略、重复、旧 tick、身份或 token 失效均不能继续旧动作
开发命令 `tlmnhdev native-ai-phase-test` 使用真实原生调用栈的受控夹具记录阶段，
`early_component` 参数会实际提前调用组件，用于检验阶段错误能否被拒绝；两者都不是世界自然跟随测评
新增采集工具和阶段接线的实际验证状态见 [Indev 进度](PROGRESS.md)

### LOOK 与三行为组合

`FollowCoreLookDriver` 处理 LOOK 的开始、持续和停止，`FollowCoreComponent` 在同一 after-native 入口按
look0、move2、follow3 执行开始 pass，再按同序执行运行 pass；不新增任务循环，也不手动推进 helper
停止仅清本组件的 LOOK，已提交的转头请求按 RC2 原生规则消费

当前可见性使用 `EntitySenses.canSee`，报告标为 `CURRENT_RC2_LOS`，不等同于现代原版的 sensor 缓存
原版 `LookAtTargetSink(45,90)` 的参数是持续时间范围，视角限速另由实体和原生 helper 提供
初始没有 LOOK 时，本帧 follow 写入目标，下一帧 LOOK 才启动；不可为了持续转头而每帧重建已停止的 LOOK

独立原版工具见 [LOOK 对照](tools/look-reference/README.md)，需使用该目录的 init 与新身份清单
`LOOK_SINK_CORE_V1` 和 `FOLLOW_MOVE_LOOK_COMPONENT_V2` 分别记录，旧任务与移动 sink 的独立结果不升级为新组合通过
原版自身 A/A、实际故障检出、移植重放和原生 controls 消费分别验收，完整 F/N 模式继续保持不可用
