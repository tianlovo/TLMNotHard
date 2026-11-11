# 外部模组适配目录

第三方可选模组的公开 API 调用、反射桥接、数据格式解析与开发期专用夹具统一归口在 `com.tianluoqaq.tlmnh.compat` 包树下。
Minecraft/Forge、GTNH 核心平台推进（如 `MaidProgressionRecipes` 的阶段合成）、GTNHLib 与基础数学库保持各自原有分层，不在这里重复套壳；
移植自上游 TLM 的类保留原包名，经领域 SPI 访问外部模组能力。

## 源集切分与目录分层

为了在编译期彻底阻断核心业务对可选模组类名、客户端渲染类以及开发探针的反向耦合，`compat` 按职责拆分为四个物理源目录：

- `src/compat/java`：双端安全的公共适配实现、16 个模组描述子（`CompatDescriptor`）与 `CompatBootstrap`，随主模组发布。
- `src/compatClient/java`：纯客户端渲染、GUI 槽位提示、旗帜/头颅桥接及 YSM 可选客户端 Mixin，随主模组发布。
- `src/devtools/java`：面向特定模组的开发探针、LWJGL 输入注入与测试构造夹具，仅打入独立附属模组 `tlmnhdevtools`。
- `tools/horizon-qa/pilot`：仅在 Horizon-QA 服务端回归中使用的区块群系读取、随机数与人口静默探针。

每个模组子包内部继续按端与职责细分，Java 源文件不直接平铺在 `<模组包名>/` 根下（唯一的模组级元数据入口 `<ModName>Compat.java` 除外）：

```text
compat/
  <模组包名>/
    <ModName>Compat.java        # 模组 ID、逻辑端、依赖边与保护特性描述子
    common/<职责>/               # 位于 src/compat/java
    client/<职责>/               # 位于 src/compatClient/java
    client/development/         # 位于 src/devtools/java
    development/<职责>/          # 位于 src/devtools/java 或 tools/horizon-qa/pilot
```

Java 包路径统一采用全小写字母与数字；真实的 Mod ID、物品注册名与目标类名则保留提供方原始大小写，并集中在 `KnownModIds` 或对应 `<ModName>Compat` 中声明。

## 注册生命周期与调用边界

1. **单向依赖与 T0 门禁**：`src/main/java` 与 `src/client/java` 禁止直接 `import` 或引用 `com.tianluoqaq.tlmnh.compat.*`，
   由构建期 `verifyArchitectureBoundaries` 任务静态拦截。
2. **启动期一次性绑定并冻结**：
   - 公共侧在 `TLMNotHard.preInit` 起始处调用 `CompatServices.preInitAndFreeze()`，经 `ServiceLoader` 加载 `CompatBootstrap`
     并冻结盾牌、武器、弹道物理、门几何、世界扩展内容、营养读取与生命周期存储七组 SPI。
   - 客户端侧在 `ClientProxy.init` 阶段调用 `ClientCompatServices.initAndFreeze()`，加载 `ClientCompatBootstrap`
     注入渲染平台状态、持物/旗帜/头颅/墓碑渲染桥接以及 `HandSlotIconHook` 双手空槽图标。
   - 冻结完成后拒绝运行期再次改写注册表；在纯单元测试环境（未加载 `compat` 源集或未启动 FML）下，各入口自动回退到安全的默认空实现。
3. **跨模组依赖无环约束**：不同模组适配子包之间默认相互独立，仅允许 `CompatModules` 显式声明的有向无环依赖边（当前仅
   `tinkersdefense -> tconstruct` 与 `tinkersdefense -> battlegear2`），由 `CompatModulesTest` 与 T0 门禁共同校验。
4. **惰性解析与故障熔断**：可选外部类型必须在 `IntegrationGuard` 保护闭包内按需触发解析，避免在类初始化或构造 lambda 时提前链接缺失的第三方 API 类；
   缺失或签名失配时按 `ExternalFeature` 独立熔断并保留原始物品与存档 NBT。

## 已归档模组一览

| 模组（子包） | 分布源集与子目录 | 主要职责 |
| --- | --- | --- |
| Angelica（`angelica`） | `compatClient`：`client/render`、`client/query`；`devtools`：`client/development` | 渲染状态与矩阵栈深度预检、过滤窗口恢复；光影切换、批次读取与缺类故障注入探针 |
| AppleCore（`applecore`） | `compat`：`common/food` | 可选营养值接口读取 |
| Backhand（`backhand`） | `devtools`：`development/player`、`client/development` | 玩家真实副手槽与切手开发夹具（生产交互直接走原生持物入口） |
| Battlegear2（`battlegear2`） | `compat`：`common/shield`；`compatClient`：`client/gui`、`client/render` | 盾牌格挡、捕箭计数协议、预览木盾查询、双手空槽图标与副手盾牌持握矩阵桥接 |
| EndlessIDs（`endlessids`） | `compat`：`common/storage`；`horizon-qa`：`development/biome` | 区块 24 位方块 ID 与 16 位 metadata 磁盘核验；QA 群系短整型数组读取 |
| Et Futurum Requiem（`etfuturum`） | `compat`：`common/block`、`common/entity`、`common/item`、`common/physics`、`common/sound`；`compatClient`：`client/render` | 气泡柱/蜂蜜/凋零玫瑰/岩浆块识别、新船与座位、盔甲架、鞘翅、水流物理、现代音效域及旗帜/头颅渲染 |
| Galacticraft Core（`galacticraftcore`） | `compat`：`common/physics` | 星球维度原生箭重力采样与发射重力校准 |
| GregTech 5 Unofficial（`gregtech`） | `compat`：`common/combat`、`common/food` | GT 近战工具识别与准备、`MetaGeneratedItem` 自定义食物与药效读取 |
| Hodgepodge（`hodgepodge`） | `compat`：`common/storage`；`horizon-qa`：`development/population` | 异步存档 `WorldDataSaver` 刷盘屏障；QA 区块人口静默观测 |
| lwjgl3ify（`lwjgl3ify`） | `devtools`：`development/input` | 客户端开发探针的鼠标与文本事件队列注入（不读写系统剪贴板） |
| MalisisDoors（`malisisdoors`） | `compat`：`common/block`、`common/combat`；`devtools`：`development/block` | 动画门开闭状态、开关门调用、战斗通行几何采样与动画门测试夹具 |
| TConstruct（`tconstruct`） | `compat`：`common/tool`；`devtools`：`development/tool` | 匠魂工具破损与 NBT 数据只读解析、开发期真实 `ToolBuilder` 构造 |
| Tinkers' Defense（`tinkersdefense`） | `compat`：`common/shield`；`compatClient`：`client/render`；`devtools`：`development/shield` | 圆盾与鸢盾的数据合同、副手渲染锚点换算及合法盾牌测试样本生成 |
| Twilight Forest（`twilightforest`） | `compatClient`：`client/render` | 按维度 `WorldProvider` 类型切换暮色森林墓碑贴图，不硬编码维度 ID |
| Witchery（`witchery`） | `compat`：`common/entity` | 女仆兑现出生前的 `WITCInitialWidth` / `WITCInitialHeight` 尺寸标签对齐 |
| Yes Steve Model（`ysmu`） | `compatClient`：`client/animation`、`client/mixin` | 玩家背负女仆时的 `passenger$touhou_little_maid:maid` 兼容别名匹配 |

此外，`tools/horizon-qa/pilot/com/tianluoqaq/tlmnh/compat/bugtorch/development/random` 归档了面向 BugTorch 实体随机源的 QA 采样辅助类。
调整任一适配类名或包路径时，请同步检查反射字符串、`mixins.tlmnh.ysmu.json` 以及 `ProtectionTests` 中的隔离类加载前缀；历史冻结验证记录保持原样，不做回溯改写。
