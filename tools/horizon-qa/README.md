# Horizon-QA 服务端试点

本工具使用 Horizon-QA **0.15.0**，对应上游提交 `e05abc05be88e9072c7ed298e4ae81c158213515`
[官方接入文档](https://www.gtnewhorizons.com/Horizon-QA/getting-started/mod-setup/)

测试源码和结构位于本目录，使用独立 source set 生成 `tlmnh-horizonqa-pilot.jar`
默认构建、玩法依赖与正式 JAR 都不包含框架或这些测试

## 构建和运行

下载同版本开发 JAR，将 `TLMNH_HQA_DEV_JAR` 指向该文件，再执行：

```text
./gradlew -I tools/horizon-qa/pilot.init.gradle reobfHorizonQaPilot
```

构建会核对开发 JAR 的 SHA-256，并沿用项目的 Jabel、Java 8 输出与 RFG 重混淆
使用同一 init 时，`spotlessJavaApply` 仅格式化本目录中的试点 Java 文件

在新的专用 GTNH 测试副本中加载玩法 JAR、Horizon-QA 运行 JAR 和试点 JAR
将以下参数传给 Minecraft 服务端 JVM：

```text
-Dhorizonqa.mode=ci
-Dhorizonqa.tests=tlmnhqapilot:AquaticLifecycleTests.liveSwimmingBody
-Dhorizonqa.gridOrigin=0,100,0
-Dhorizonqa.turbo=1
-Dhorizonqa.reportDir=<独立报告目录>
```

使用 RFG 的 `runServer` 时，每个参数分别通过 `--mcJvmArgs` 转交，不能把这些 `-D` 参数设置到 Gradle JVM

正常用例应退出 0；加上 `-Dtlmnh.qa.injectPoseFault=true` 后，应因真实泳姿身体被改成 1.5 高而退出 1
故障模式用于核对检查器，不能作为正常通过运行

## 试点结论与边界

GTNH 2.9.0、Java25、Indev94 上的正常用例通过，故障版准确报告 `expected height=0.6 actual=1.5`
两轮 JUnit XML、状态 JSON 和实际进程退出码一致，均确认实体清理及全局动物开关恢复
初次因 CI 动物策略导致实体被删除的失败单独保留

Horizon-QA 的 CI 默认会调用 `setCanSpawnAnimals(false)`；旧 WorldServer 会因此逐 tick 删除 EntityAnimal
女仆继承该类型，所以用例先允许动物实体运行，并用 `afterTest` 恢复原值；这是一项测试前提，不替代女仆行为
改变这个全局状态的测试须独立批次运行，不能靠测试格距离获得隔离

本用例只完成一次自然入水观测和受控状态转换；睡眠、醒来请求在同一 END 阶段完成，不代表真实床行为或长期稳定性
后续新增服务端用例可复用其调度、场景与报告，逐步接入，不批量重写已有测试
客户端画面、完整水域行为和原版 1.20.1 的 O/F/N 对照仍使用各自验证流程
