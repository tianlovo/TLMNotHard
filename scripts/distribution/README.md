# TLM 双端增量包

打包工具默认生成本地验证包，也支持显式审批后的正式发行渠道。两种渠道均完整保留候选 JAR 中的模型、贴图、动画与声音资源。
完整性检查通过仅说明包内资料齐全，正式发布准入仍需单独完成。

## 生成

在工程根目录执行以下命令，并按实际替换版本号、SHA 与冻结输入目录：

```powershell
python scripts/package_overlay.py build/libs/tlmnh-VERSION.jar `
  --sha256 CANDIDATE_SHA256 `
  --input-snapshot FROZEN/input-snapshot.json `
  --source-root FROZEN/main-source `
  --previous-jar OLD/tlmnh-OLD.jar --output dist
```

`--previous-jar` 参数可重复传入，按所供文件的实际文件名与 SHA 登记允许替换的旧版本。
既未在此登记、也不在独立 TLM 托管记录中的旧 JAR 会被拒绝替换，工具不会仅凭文件名猜测并删除文件；同名旧 JAR 同样必须匹配已登记的 SHA。

打包器会核对完整的候选审计结果、显式指定的 JAR SHA，以及冻结 `src/main` 全量文件的枚举清单与逐文件 SHA。
文件数量由传入快照决定，不写死版本号或固定数量。
这些记录用于建立冻结输入到候选产物的对应关系，不能替代可复现构建证明。

输出分为 `client` 和 `server` 两个 ZIP 包，两端包含字节完全相同的 JAR。
包内保留六份第三方许可证原文、原作者署名、来源清单、候选审计报告、源码输入摘要及 `SHA256SUMS.txt`。
中文玩家使用与配置说明位于 `.tlmnh-distribution/<端别>/guides/USER_GUIDE.md`，英文版为 `USER_GUIDE.en.md`，
对应源码位于 `docs/`；两份指南一并纳入包清单与哈希摘要，随 TLM 资料统一托管。
可移植清单不包含本机目录路径、运行存档或安装日志。
两个 ZIP 会先在同一输出目录中完整暂存并校验摘要，随后再落盘为最终文件名。
若第二个 ZIP 发布失败，工具仅清理本次新建且身份与 SHA 仍匹配的文件，未知后继文件予以保留。
重试时可复用摘要完全一致的完整 ZIP，已存在但字节不同的同名文件不会被覆盖。

默认渠道为 `--channel local-validation`，生成包名与交互界面均明确标示本地验证用途。
若指定 `--channel release`，遇到配音分发未放行、原创许可未就绪或处于 Indev 状态时直接拒绝打包，严格保障发布合规。

## 更新与回滚

1. 将所选端别的 ZIP 解压到目标实例目录之外。
2. 正常退出目标客户端或服务端。
3. 安装交互菜单依赖：`python -m pip install rich`。
4. 在解压目录内运行 `python update.py`，通过 Rich 菜单选择更新或回滚。

客户端请选择包含 `mmc-pack.json` 的实例目录，或直接选择其下的 `.minecraft`；服务端请选择包含 `lwjgl3ify-forgePatches.jar` 的目录。
更新工具仅在游戏退出后管理模组文件与备份，不修改整合包全局配置。
新生成的 `config/tlmnh.cfg` 默认启用 Enhanced；已有显式指定的 Legacy 配置保持原值，修改策略后需重启服务端。

TLM 使用 `.tlmnh-managed-client.json` 与 `.tlmnh-managed-server.json` 记录自身托管的 JAR 与配套资料，
完整保留整合包原有的 `.gtnh-managed-*.json` 字节及其余托管条目。
配套资料位于 `.tlmnh-distribution/<端别>`，历史备份位于 `.tlmnh-update-backups/<端别>`。
`.tlmnh-update-lock-<端别>` 仅用于更新与回滚互斥，进程退出后由操作系统自动释放锁。

每次修改前都会保存 `before.zip` 与 `receipt.json`，记录原始字节、目标字节 SHA 及操作状态。
对同一验证包重复执行更新时会返回“已是最新”，不再重复生成备份。
操作中途失败会尝试恢复原始文件；若进程意外中断，可通过回滚菜单恢复未完成的记录。
执行恢复前会先核查全部目标文件，遇到未知后继版本、用户手工改写或更新的记录时均保留现场，不直接覆盖。
多次更新按由新到旧的逆序逐步回滚。
此处的回滚仅恢复 TLM JAR、配套资料及对应托管记录，不会回滚世界存档或整包配置。
若新版已保存过世界且需要降级，须先正常停止两端、保留新版世界副本，再一并恢复升级前配对的旧双端 JAR、完整世界目录与配置文件。
完整世界包含全部维度、`playerdata`、实体区块及生命周期账本，不能仅替换 JAR、还原单个玩家存档或手工修改 schema 编号。
目标文件更新在互斥锁内执行写入前、写入后摘要核对与单文件原子替换；该锁无法阻止未遵循此锁的外部进程在检查与写入间隙修改文件，因此操作前必须先正常退出目标实例。

工具会逐项校验路径，拒绝目录越界、Windows 设备别名以及目标目录内部的符号链接或 Junction；目标根目录按显式选定的真实路径识别，不会跟随内部重定向写入外部位置。
在 Windows 下，工具仅核对与所选端别及真实游戏目录匹配的 `process.json` 中记录的 PID、实际进程创建时间与退出状态，不会将复用旧 PID 的新进程误判为原实例，也不读取外部进程命令行或其他凭据。
原生 `session.lock` 仅作辅助检查；1.7 的时间戳文件与多人客户端根目录均无法证明进程已退出。
对于无匹配启动记录的其他启动器，仍须人工正常退出游戏，上述检查不保证覆盖所有启动器状态。

## 来源与验证范围

本工具沿用了用户提供的 `tools/update.py` 中的 Rich 菜单、配置合并辅助函数及 `before.zip` / `receipt.json` 备份机制，
原始文件 SHA-256 为 `fda4960b32dd14be6b47868b2edeabc411a5928c955643c371309607a1a91544`。
本仓库副本在此基础上补充了独立 TLM 托管范围、精确哈希准入、冲突现场保留及可恢复的逐文件原子更新；整合包原有的更新器保持原位，不会被此包替换。

增量更新单测入口为 `python -m unittest discover -s scripts -p test_overlay_update.py`。
测试默认使用仓库同级 `cache/validation/overlay-update-tests` 下的独立临时目录，也可通过环境变量 `TLMNH_UPDATE_TEST_ROOT` 指定自有 QA 临时根目录，全程不连接游戏实例。
实际候选的双端打包、更新与回滚仍应在合批验证阶段另行核验。
`audit_candidate_distribution.py --local-zip` 仅生成供审计使用的静态目录布局，不包含上述交互式更新器；需要可交互更新的双端验证包时，
请使用本页介绍的 `package_overlay.py` 入口。

## 正式发布审批输入

日常本机试玩继续使用默认的 `local-validation` 渠道，无需填写正式发布审批单。
正式发布需显式传入 `--channel release --release-approval APPROVAL.json`，输入文件必须是对实际候选产物的正式批准记录。

审批记录严格绑定候选版本号、JAR SHA、完整源码输入快照、原作者署名与资源来源清单，以及随包分发的指南与更新器哈希摘要。
原创适配代码必须具备已选定的正式许可证，候选 JAR 内须包含 `META-INF/TLMNH-LICENSE.txt`，且第三方说明中须包含明确的 `TLMNH original code license: SPDX` 声明行。
两套保留音频包均须登记实际适用的发布依据、署名方式与报告义务。固定音频来源清单保留原始字节，包含历史 `audio_distribution_cleared=false`。具体发行准入由候选 SHA 绑定的独立记录裁决。

核心玩法、benchmark、模型与装备、多人持久化及玩家实机试玩五个维度均需提供明确的接受决定与非空独立证据文件摘要。
benchmark 部分需如实记录现有十四类场景的评估结果与已知限制；容忍余量 δ 可以保持 `null`，不会因为未设定正 δ 而阻止提交试玩候选；若正式选定零或非零数值余量，需另行绑定对应的正式决策记录，不由打包工具自动设定。

正式版 ZIP 会附带可移植的审批摘要与原创代码许可证全文，不会打包本机私有证据路径、审批人机器目录或本地隐私路径。
完整性校验、正式发布审批与统计显著性结论彼此独立，工具不会把数据采集完成自动推导为业务验收通过或统计等价。

### v2 音频记录

`--release-approval` 使用 `tlmnh-release-approval-v2`。旧 v1 记录需补齐独立音频证据后重新核定。
现有候选版本、JAR SHA、源码输入快照、随包文档、许可、来源及五项接受决定继续校验。
`audio_packs` 恰含 `littlemaid_peco` 和 `touhou_little_maid` 两项，每项保留以下字段。

| 字段 | 内容 |
| --- | --- |
| `decision` | 实际发行决定，准入值为 `approved` |
| `basis` | 本次适用依据 |
| `publication_steps` | 非空步骤说明列表 |
| `evidence` | 独立音频记录 JSON 的 `path` 和 `sha256` |

每份 `evidence` JSON 使用 `tlmnh-audio-publication-evidence-v1`，填写以下字段。
表中的状态须由实际审阅和证据支持。

| 字段 | 内容 |
| --- | --- |
| `schema` | `tlmnh-audio-publication-evidence-v1` |
| `pack` | 所属音包 ID |
| `decision` | 实际发行决定，准入值为 `approved` |
| `candidate` | 与总审批完全相同的 `version`、`sha256`、`source_snapshot_sha256` |
| `provenance_sha256` | 候选包内固定 `assets/tlmnh/builtin-sounds-provenance.json` 的完整 SHA |
| `terms.url`、`terms.revision`、`terms.language` | 实际采用的原条款地址、版本和语言 |
| `terms.document` | 保存的原条款文件的 `path` 和 `sha256` |
| `terms.applicability` | `decision=applicable`、具体 `assessment`、独立 `evidence` 文件及 SHA |
| `terms.extractable_archives` | 实际依据允许主 JAR 与源码 JAR 可提取音频时填 `permitted` |
| `authorization` | 现有有效授权的 `decision=authorized` 及独立 `evidence` 文件和 SHA |
| `attribution` | 已落实署名的 `decision=completed` 及独立 `evidence` 文件和 SHA |
| `obligations` | 条款要求的其他义务列表。逐条登记，确无其他义务时显式使用 `[]` |

条款、适用性、授权和署名证据均以文件原字节核验摘要。音频记录内的相对路径相对于该记录所在目录，
总审批中的音频记录和接受证据路径相对于总审批所在目录。证据应保存在私有发行资料中，正式 ZIP 仅附摘要和状态。
具体条款适用及证据真实性由审批者核定，工具核对已登记的状态、绑定关系和字节摘要。

Amitaro 日文条款适用于能够读懂日文者，采用该条款时须在适用性记录中明确
`japanese_comprehension=confirmed`，并由该记录的独立证据支持。
使用其英文条款时，应核对可提取 JAR 的实际分发形态及适用授权。
两版条款分别见 [日文原文](https://amitaro.net/voice/voice_rule/) 和 [英文原文](https://amitaro.net/voice/terms/)。
PEKO/PECO 的原说明见 [作者发布页](https://booth.pm/ja/items/1903163)。

### 义务状态与事后报告

每条义务均须具有唯一可移植 `id`、实际 `action`、原条款 `clause`、`timing` 和 `status`。
`id` 使用 1～80 个英文字母、数字、下划线、点或连字符，首字符为字母或数字。
`timing` 可为 `before_release` 或 `after_publication`，应依据已归档条款登记。

- `completed` 须提供 `completed_on` 和独立 `evidence` 文件及 SHA，完成日期不得晚于审批日期。
- `before_release` 的义务必须完成。
- 条款允许事后履行时，`after_publication` 可登记为 `pending`，并填写正整数 `due_within_days`。
  `published_on` 填实际触发该义务的公开日期，尚未公开时填 `null`。
  已公开的义务在审批日期及实际 UTC 评估日均须处于期限内。既有公开版触发的报告应使用其真实日期。
- `pending` 记录保留未完成状态，附实际待办动作，不填写发送证据或完成日期。

以下为尚未发送的事后义务字段示例，应在适用条款核定后放入对应音频记录。

```json
{
  "id": "author-report",
  "action": "按适用条款向作者提交实际发行报告并保存凭据",
  "clause": "填写已保存原条款中的具体事后报告条款位置",
  "timing": "after_publication",
  "status": "pending",
  "due_within_days": 30,
  "published_on": null
}
```

只有实际条款支持该期限与履行顺序时才能使用以上示例。作者要求先取得许可的情形应登记为前置义务并核实完成证据。
用户已有的完整资产发布授权可以直接作为现有授权证据，所需作者手续依据实际条款另行登记。

审批日期不得晚于程序读取的实际 UTC 评估日。每次打包都会重新核验已公开的待办期限，
过去批准的记录在义务逾期后会被拒绝。评估日期由运行时钟读取，并记入发行摘要的 `evaluated_on`。

正式 ZIP 的 `release-decision.json` 保留各音包证据摘要及每项义务的真实状态，包括待办期限与公开日期。
外层 `manifest.json` 的 `audio_distribution_cleared` 表示本候选已通过具体发行审批，
`audio_source_distribution_cleared` 记录固定来源清单中的历史值。主 JAR 和来源清单按原字节随包保留。
默认本地验证渠道的发行状态保持 `false`。
