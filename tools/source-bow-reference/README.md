# 原弓箭数值对照

`SourceBowMathOracle.java` 在固定 Minecraft 1.20.1 映射类上运行 Vec3、Mth 和 RandomSource
使用 Java 17，不启动游戏，也不读取移植版实现

七组输入覆盖零向量、归一化边界、近中远距离、速度上下限与随机散布
输出保存实际加载类的 SHA-256，以及伤害和三个速度分量的 double 位模式
伤害与飞行公式分别依据固定 TLM `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6` 的 TaskBowAttack，
及其映射 AbstractArrow、Projectile 方法；它只检查数值适配，不代替原实体、自然 AI 或统计 benchmark

本地为 `TLMNH_ORACLE_CLASSPATH` 提供已核对的原版映射运行类路径后，在工程根执行：

```powershell
javac -encoding UTF-8 -cp $env:TLMNH_ORACLE_CLASSPATH -d build/source-bow-oracle tools/source-bow-reference/SourceBowMathOracle.java
java -cp "build/source-bow-oracle;$env:TLMNH_ORACLE_CLASSPATH" com.tianluoqaq.tlmnh.reference.SourceBowMathOracle build/source-bow-oracle.tsv
```

复核输出的类 SHA、输入和差异后，才可更新 `src/test/resources/fixtures/source-bow-oracle.tsv`
移植版 `SourceBowShotTest` 用 Java Random 重放同一输入，逐位比较七组伤害和速度；不自动接受新结果
