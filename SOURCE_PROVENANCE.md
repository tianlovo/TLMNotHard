# 上游移植记录

固定上游为 TartaricAcid/TouhouLittleMaid 的 1.20 分支，提交 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6，代码许可见 licenses/TouhouLittleMaid-MIT.txt

| 工程路径，均位于 src/main/java/ | 上游同路径 | 移植与改动 |
| --- | --- | --- |
| com/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid.java | 是 | 将 TamableAnimal 和 SynchedEntityData 转为 1.7.10 EntityTameable 与 DataWatcher，保留默认模型 ID、任务 ID、日程、经验、饥饿、好感和 NBT 键；装备交给原版实体存储，副手单独保存与同步，基础背包开放六格；接入蛋糕首次驯服、主人界面与潜行空手坐姿，行为与其余交互仍待接入 |
| com/github/tartaricacid/touhoulittlemaid/entity/ai/brain/MaidSchedule.java | 是 | 保留 DAY、NIGHT、ALL，移除高版本专用 EntityDataSerializer，由实体字节字段同步 |

新增的背包视图、注册和测试命令位于 com.tianluoqaq.tlmnh，属于本移植工程的独立适配文件，没有复制其他研究候选的实现

## Bedrock 几何

以下 16 个原文件均来自上述固定提交，位于 `com/github/tartaricacid/simplebedrockmodel/client/bedrock/`，保留原包名、原说明和代码许可头

| 相对路径 | 适配内容 |
| --- | --- |
| pojo/BedrockModelPOJO.java、BedrockVersion.java、BonesItem.java、CubesItem.java、Description.java、FaceItem.java、FaceUVsItem.java、GeometryModelLegacy.java、GeometryModelNew.java | 保留格式解析、骨骼、镜像继承与逐面 UV；版本比较改为 Forge 自带接口，方向枚举改为独立的六方向枚举 |
| model/BedrockPart.java、BedrockCube.java、BedrockCubeBox.java、BedrockCubePerFace.java | 保留层级变换、方块旋转与顶点顺序；改用 LegacyPoseStack 和 LegacyVertexConsumer，集合改为 JDK 类型，数学常量和接口改为 1.7.10 可用形式 |
| AbstractBedrockEntityModel.java、BedrockModelProvider.java、BedrockModelUtil.java | 改接 ModelBase、Entity 与 AxisAlignedBB；移除高版本 RenderType 和 Sodium 专用分支，保留普通几何路径；补上 ModelBase 渲染入口，并让 renderToBuffer 的颜色参数传入骨骼 |

新增矩阵栈、顶点接口和 Tessellator 适配位于 `com.tianluoqaq.tlmnh.client.render`，JOML 按官方 Shadow 配置重定位到本工程包下；矩阵法线以余子式计算，单轴缩放为零时仍能保留平面法线，提交时再归一化

CPU 测试覆盖 205 份非 Gecko 内置几何，以及层级与独立方块旋转、镜像、逐面 UV、零厚度平面和法线；这不表示已经注册实体渲染器，也不表示 Gecko、动画或 Angelica 下的实际画面已通过验收

内置女仆模型的原始资源已导入，逐文件原路径与 SHA-256 见 src/main/resources/assets/tlmnh/builtin-assets-provenance.json，原始说明与作者信息保留在各命名空间的 maid_model.json 和 lang/*.json；资产许可见 THIRD_PARTY_NOTICES.md，渲染、动画和声音播放仍在后续实现范围
