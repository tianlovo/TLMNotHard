# TLM 双端增量包

当前生成的是 Indev 本地验证包，完整保留候选 JAR 的模型、贴图、动画和声音
完整性检查通过只说明包内资料齐全，正式发布准入仍需另外完成

## 生成

在工程根目录运行，替换命令中的版本、SHA 和冻结目录

```powershell
python scripts/package_overlay.py build/libs/tlmnh-VERSION.jar `
  --sha256 CANDIDATE_SHA256 `
  --input-snapshot FROZEN/input-snapshot.json `
  --source-root FROZEN/main-source `
  --previous-jar OLD/tlmnh-OLD.jar --output dist
```

`--previous-jar` 可重复，按提供文件的实际名字和 SHA 登记可替换的旧版本
没有登记、也没有独立 TLM 托管记录的旧 JAR 会被拒绝，不按文件名猜测后删除
同名旧 JAR 也必须匹配登记的 SHA

打包器核对完整候选审计、明确的 JAR SHA 和冻结 `src/main` 全部文件的枚举及逐文件 SHA
文件数由传入快照决定，不固定版本号或数量
这些记录提供冻结输入到候选的关联，不能代替可复现构建证明

输出分为 client 和 server 两个 ZIP，使用字节相同的 JAR
包内保留六份许可证、原署名、来源清单、候选审计、源码输入摘要和 `SHA256SUMS.txt`
玩家使用与配置说明位于 `.tlmnh-distribution/<端别>/guides/USER_GUIDE.md`，英文为 `USER_GUIDE.en.md`
对应源码文件在 `docs/`；两份指南同样纳入包清单和摘要，随 TLM 资料管理
便携清单不包含本机目录、运行存档或安装日志
两个 ZIP 先在同输出目录中完整暂存并核对摘要，再写入最终文件名
第二个 ZIP 发布失败时，只清理本次创建且身份和 SHA 仍匹配的文件；未知后继保留
重试可以复用摘要完全相同的完整 ZIP，已有不同字节的同名文件不会覆盖

默认 `--channel local-validation`，包名和交互界面均标明本地验证
`--channel release` 遇到现有配音分发状态、原创许可、Indev 等发布阻碍时拒绝生成
该选项不会变更许可状态，也不会删减音频来通过检查

## 更新与回滚

1. 将所选端的 ZIP 解压到目标实例之外
2. 正常退出目标游戏或服务端
3. 安装交互依赖：`python -m pip install rich`
4. 运行解压目录内的 `python update.py`，沿 Rich 菜单选择更新或回滚

客户端选择含 `mmc-pack.json` 的实例目录，也可选择它下面的 `.minecraft`
服务端选择含 `lwjgl3ify-forgePatches.jar` 的目录
工具不启动游戏，也不写入整包配置

TLM 使用 `.tlmnh-managed-client.json` / `.tlmnh-managed-server.json` 记录自己管理的 JAR 和资料
保留原整包 `.gtnh-managed-*.json` 的字节及其余托管内容
安装资料位于 `.tlmnh-distribution/<端别>`，备份位于 `.tlmnh-update-backups/<端别>`
`.tlmnh-update-lock-<端别>` 只供更新和回滚互斥，进程退出后系统会释放锁

每次修改前保存 `before.zip` 和 `receipt.json`，记录原字节、目标字节的 SHA 和操作状态
重跑相同包时返回“已是最新”，不再创建备份
操作失败会尝试恢复原文件；进程被中断后，可沿回滚菜单恢复未完成记录
恢复前先检查全部目标，未知后继、用户改写或较新的更新都保留，不直接覆盖
多次更新按从新到旧的顺序回滚
这里的回滚只恢复 TLM JAR、资料及其托管记录，不回滚存档或整包配置
新版已保存世界后若要降级，先正常停两端，保留新版世界副本，再恢复升级前配对的旧双端 JAR、完整世界和配置
完整世界包括各维度、playerdata、实体区块及生命周期账本，不能只替换 JAR、还原单个玩家文件或修改 schema 数字
目标文件采用更新锁内的写入前、写入后摘要核对及单文件原子替换
它不能阻止未使用此锁的外部程序在检查与写入之间修改文件，因此必须先正常退出目标

路径逐项核对，拒绝目录越界、Windows 别名及目标内部的符号链接或 Junction
目标根目录按明确选定的真实路径识别，不跟随其内部重定向去写其他位置
Windows 下只核对所选目标匹配端别和真实游戏目录的 `process.json` 中记录的 PID、实际创建时间与退出状态
较新的复用 PID 不会被误认为该记录的进程，不读取命令行或其他进程凭据
原生 `session.lock` 仅作为补充检查；1.7 的时间戳文件及多人客户端根目录都不能证明进程已退出
没有匹配启动记录的其他启动器仍须人工正常退出，这些检查不保证所有启动器都已停止

## 来源与验证范围

工具保留用户提供的 `tools/update.py` 的 Rich 菜单、配置合并辅助函数以及 `before.zip` / `receipt.json` 方式
原始文件 SHA-256 为 `fda4960b32dd14be6b47868b2edeabc411a5928c955643c371309607a1a91544`
本副本增加独立 TLM 范围、精确摘要准入、冲突保留和可恢复的逐文件更新
原整包更新器保持原位，不被这个包替换

增量测试入口为 `python -m unittest discover -s scripts -p test_overlay_update.py`
测试默认使用仓库相邻 `cache/validation/overlay-update-tests` 下的独立临时目录
也可用 `TLMNH_UPDATE_TEST_ROOT` 指定自有 QA 临时根目录，不连接游戏
实际候选双端打包与更新、回滚应在合批时另行核对
`audit_candidate_distribution.py --local-zip` 只产生审计用静态布局，不包含上述交互更新器
需要可更新的双端验证包时，使用本页的 `package_overlay.py` 入口
