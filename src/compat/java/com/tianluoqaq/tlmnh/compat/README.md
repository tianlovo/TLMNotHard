# 外部模组适配目录

独立第三方模组的公开 API 调用、反射绑定、数据格式解析与专用开发夹具统一归档在本目录。
Minecraft/Forge、GregTech/GTNH 核心平台、GTNHLib 及通用基础库继续保持原有分层，不在此处重复包装。
移植的上游 TLM 文件保留原有包名，业务层通过本目录提供的适配入口访问外部模组能力。

## 分层

```text
compat/
  <模组名>/
    common/<职责>/
    client/<职责>/
    client/development/
    development/<职责>/
```

各模组子目录按实际职责按需建立分支，Java 源文件不直接放在 `<模组名>/` 根目录下。
`common` 存放双端均可安全加载的适配逻辑，`client` 存放渲染与界面等纯客户端代码，`development` 存放面向特定提供方的测试构造、状态观测与故障注入夹具。
包路径统一使用全小写 Java 包名，真实的 Mod ID、物品注册名与外部类名则保持提供方原有的大小写。

## 现有分类

| 模组（目录） | 模组内子目录 | 已归档内容 |
| --- | --- | --- |
| Angelica（`angelica`） | `client/render`、`client/query`、`client/development` | 渲染状态、纹理与光影查询、批次观察、接口缺失夹具 |
| AppleCore（`applecore`） | `common/food` | 营养数据读取 |
| Backhand（`backhand`） | `development/player` | 真实副手槽及切手夹具；生产交互沿用其修改后的原生持物入口 |
| Battlegear2（`battlegear2`） | `common/shield`、`client/gui` | 盾牌接口、受支持物品、格挡音效与原生双手空槽提示 |
| Et Futurum Requiem（`etfuturum`） | `common/block`、`common/entity`、`common/item`、`common/sound` | 方块分类、船与座位、盔甲架、鞘翅识别、现代声音资源域 |
| Galacticraft Core（`galacticraftcore`） | `common/physics` | 原生箭重力查询 |
| lwjgl3ify（`lwjgl3ify`） | `development/input` | 客户端探针的公开文本事件输入；不访问剪贴板 |
| MalisisDoors（`malisisdoors`） | `common/block`、`development/block` | 动画门状态、门与栅栏门开关、动画完成态夹具 |
| TConstruct（`tconstruct`） | `common/tool`、`development/tool` | 工具 NBT、构造事件身份、真实 ToolBuilder |
| TinkersDefense（`tinkersdefense`） | `common/shield`、`development/shield` | 两种盾牌的数据合同、合法物品构造与取样 |
| Twilight Forest（`twilightforest`） | `client/render` | 通过实际维度提供方类型选择原版墓碑纹理，不写死维度号 |

## 调用边界

通用 AI 决策、进食流程、盾牌结算与渲染主干继续留在业务层，具体提供方的接口绑定与数据格式判定由本目录对应子包负责。
跨模组适配可直接调用其明确的上下游依赖，例如 TinkersDefense 的盾牌夹具复用 TConstruct 的 `ToolBuilder`。
诊断元数据、依赖清单与测试输入允许保留目标模组或物品 ID 字符串，不必仅因引用标识符就将整个业务类迁入特定模组目录。

可选外部 API 必须在既有的 `IntegrationGuard` 保护闭包内惰性解析，严禁在构造方法引用或初始化公共注册表阶段提前触发类加载。
适配实现须严格维护逻辑端边界、故障隔离范围、物品所有权与存档字段兼容性；客户端类型不得成为公共服务端类的加载依赖。
调整适配包名后，需同步更新 Java 引用、反射类名字符串及现行夹具的隔离类加载范围，历史冻结证据则保持原状不改写。
