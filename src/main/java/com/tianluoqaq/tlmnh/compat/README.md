# 外部模组适配目录

独立模组的 API 调用、反射绑定、数据格式和专用开发夹具集中在这里
Minecraft/Forge、GregTech/GTNH 核心平台、GTNHLib 及通用核心库继续保留原有分层，不在这里重复包装
原 TLM 文件保留上游包名，业务类调用适配入口

## 分层

```text
compat/
  <模组名>/
    common/<职责>/
    client/<职责>/
    client/development/
    development/<职责>/
```

按实际职责创建需要的分支，Java 文件不直接放在模组名目录下
`common` 放双端可加载的适配，`client` 放渲染等客户端代码，`development` 放提供方专用的构造、观察和故障夹具
目录使用小写 Java 包名；真实加载器 ID、物品注册名和外部类名保持提供方原有大小写

## 现有分类

| 模组（目录） | 模组内子目录 | 已归档内容 |
| --- | --- | --- |
| Angelica（`angelica`） | `client/render`、`client/query`、`client/development` | 渲染状态、纹理与光影查询、批次观察、接口缺失夹具 |
| AppleCore（`applecore`） | `common/food` | 营养数据读取 |
| Backhand（`backhand`） | `development/player` | 真实副手槽及切手夹具；生产交互沿用其修改后的原生持物入口 |
| Battlegear2（`battlegear2`） | `common/shield` | 盾牌接口、受支持物品与格挡音效 |
| Et Futurum Requiem（`etfuturum`） | `common/block`、`common/entity`、`common/item` | 方块分类、船与座位、盔甲架、鞘翅识别 |
| Galacticraft Core（`galacticraftcore`） | `common/physics` | 原生箭重力查询 |
| MalisisDoors（`malisisdoors`） | `common/block`、`development/block` | 动画门状态、门与栅栏门开关、动画完成态夹具 |
| TConstruct（`tconstruct`） | `common/tool`、`development/tool` | 工具 NBT、构造事件身份、真实 ToolBuilder |
| TinkersDefense（`tinkersdefense`） | `common/shield`、`development/shield` | 两种盾牌的数据合同、合法物品构造与取样 |

## 调用边界

通用 AI、进食、盾牌和渲染流程继续留在业务层；提供方接口与格式判断由相应子目录负责
跨模组适配调用其明确依赖，例如 TinkersDefense 的盾牌夹具使用 TConstruct 的 ToolBuilder
诊断元数据、依赖清单和测试输入可以保留模组或物品 ID，不因此把整个业务类移进某个模组目录

可选 API 必须在既有 `IntegrationGuard` 保护闭包内惰性解析，不能在创建方法引用或初始化公共注册表时提前加载
保留逻辑端、故障范围、物品所有权与存档字段；客户端类不能成为公共服务端类的依赖
移动包名后同步更新 Java 引用、反射类名和当前夹具的隔离加载范围，历史冻结证据保持原状
