# 原弓箭数值对照

`SourceBowMathOracle.java` 在固定的 Minecraft 1.20.1 映射类上直接运行 `Vec3`、`Mth` 与 `RandomSource`。
该工具使用 Java 17 独立运行，不启动游戏实例，也不读取移植版实现。

七组输入覆盖零向量、归一化边界、近中远距离、速度上下限与随机散布。
输出文件会记录实际加载类的 SHA-256，以及伤害值与三个速度分量的 `double` 精确位模式。
伤害与弹道递推公式分别依据固定上游 TLM 提交 `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6` 的 `TaskBowAttack`，以及对应映射下的 `AbstractArrow` 和 `Projectile` 方法；该工具仅检验数值公式适配，不替代真实实体碰撞、自然行为 AI 或统计 benchmark。

在本机为 `TLMNH_ORACLE_CLASSPATH` 配置好已核对的原版映射运行类路径后，于工程根目录执行：

```powershell
javac -encoding UTF-8 -cp $env:TLMNH_ORACLE_CLASSPATH -d build/source-bow-oracle tools/source-bow-reference/SourceBowMathOracle.java
java -cp "build/source-bow-oracle;$env:TLMNH_ORACLE_CLASSPATH" com.tianluoqaq.tlmnh.reference.SourceBowMathOracle build/source-bow-oracle.tsv
```

人工复核输出文件中的类 SHA、输入用例与数值差异无误后，方可更新 `src/test/resources/fixtures/source-bow-oracle.tsv`。
移植版单测 `SourceBowShotTest` 使用 `java.util.Random` 重放同一组输入，逐位比对七组伤害与速度分量，不会自动覆盖或放行新基线。
