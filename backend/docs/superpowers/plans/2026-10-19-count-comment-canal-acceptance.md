# 全量计数、评论恢复与 Canal 链路验收计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本文件是计划，勾选项必须在实际执行并取得证据后更新。

**Goal:** 完成全量计数审计及已发现差异的逐条修复，并按实际证据更新交付记录；评论与 Canal 故障恢复实测因本机内存不足暂缓。

**Architecture:** 现有运行栈只做只读全量计数审计；用户确认稳定差异来源后，另用有行级前置条件的事务逐条修复。评论和搜索的故障注入原计划在独立 Docker 网络、数据库、消息队列和存储中运行真实业务服务；由于本机可用内存不足，目前保持隔离项目 `xiaohashu-accept-7ba05531ecfe` 的容器停止并延期，不影响共享容器。

**Tech Stack:** Windows PowerShell、Docker Compose、JDK 17、Maven、Spring Boot 3、MySQL 8、Redis、RocketMQ 5.3.1、Cassandra 5、Canal 1.1.7、Elasticsearch 7.3.0。

**Spec:**
- `docs/superpowers/specs/2026-10-18-count-search-consistency-design.md`
- `docs/superpowers/specs/2026-10-18-comment-delivery-and-cache-design.md`
- `docs/superpowers/specs/2026-10-18-note-content-and-snowflake-id-design.md`
- 用户已确认本人手机收到真实测试短信；2026-10-19 用户将当前优先级调整为修复计数审计差异并更新文档，评论和 Canal 故障恢复验收等内存充足后继续。

## 一、基线与范围

- 项目根：`F:\code\xiaohashu\xiaohashu`。
- 计划建立时 HEAD：`50ae42c`，`fix(reliability): reconcile counters and verify recovery`。
- 教程主线已到 28.10。最近记录的跨模块测试为 278 项通过、13 项条件跳过；这些是历史结果，不能作为本计划执行结果。
- 七项日增量标记对账已实现；全历史实体审计及本轮逐条修复结果见“任务 2 执行结果”。
- 已做独立 Broker、Cassandra、Canal 容器重启后的客户端恢复探测；仍缺真实评论业务的故障积压恢复，以及搜索调度器消费真实 binlog 后恢复 ES 的完整验收。
- 短信已完成接口返回成功、阿里云返回 `OK`、本人手机收到三个层面的确认。计划不再把“真实短信未发送”列为待办。
- **范围外：** FRP 公网访问继续等服务器；验证码写入 auth 日志仍是单独的待修问题；本计划不包含新一轮短信发送、全库自动改数、持续吞吐压测或 Cassandra 永久数据丢失恢复。
- 本计划后续追加实际执行记录；不得将未运行的评论/Canal 故障场景标成通过。

## 二、全局约束

1. 保留 `.idea/encodings.xml`、`AGENTS.md`、`HANDOFF-CODEX.md` 的本地状态，不暂存、不提交。
2. 项目根 `.evn` 不读、不写、不提交。验收栈使用新生成的本地测试凭据，不复制阿里云密钥；不得输出 `infra/.runtime/compose.env` 内容或完整展开后的 Compose 配置。
3. 禁止批量删除文件或目录。测试清理逐个核对具体文件、数据库行、Redis key 和容器；不使用递归删除、通配删除或 `compose down -v`。
4. 不停止、不重启、不重置现有共享业务容器、基础设施或消费位点。全量计数审计本身只读；仅在用户明确要求后，允许对已复核的具体计数行执行受保护的逐条修复。
5. 故障环境采用独立 Compose 项目、网络、持久化路径和进程；不得挂载 `F:\docker` 现有数据或加入 `xiaohashu` 共享网络。
6. 每次运行使用 `RunId=accept-` 加 12 位随机十六进制字符；ID、UUID、标题和正文使用随机测试值。manifest 先记计划资源，再记已创建资源。
7. Java/Maven 显式使用 `D:\java\jdk-17.0.18+8`；Maven 不加 `-o`。Docker 命令设置 `MSYS_NO_PATHCONV=1`。不输出短信验证码、令牌、密钥或真实评论正文。
8. 仅使用本机已存在的项目镜像和基础设施镜像，执行脚本不得自动拉取镜像。启动前检查磁盘及 Docker 可用内存；不满足隔离环境需求时报告资源缺口，不通过停止共享栈腾资源。
9. 编译成功不足以完成验收。每个场景必须启动相应服务，通过实际 HTTP/消息/binlog/Cassandra/ES 路径取得结果。
10. 每个独立交付一提交；`git add` 列具体文件。格式为 `type(scope): English summary` 加中文正文；无 push、merge。

## 三、文件与执行顺序

以下相对路径均以项目根为基准；列为“新增”的文件在执行计划时创建。

| 工作 | 新增文件 | 现有依据/可能修复位置 |
| --- | --- | --- |
| 2：全量审计 | `database/audit/full-count-audit.sql`、`infra/Invoke-FullCountAudit.ps1` | `xiaohashu-data-align/src/main/resources/mapper/CountAuditMapper.xml`、`xiaohashu-count/xiaohashu-count-biz/src/main/resources/mapper/CountSourceRefreshMapper.xml` |
| 3/4：独立环境 | `infra/acceptance/compose.recovery.yaml`、`infra/acceptance/Prepare-RecoveryFixture.ps1`、`infra/acceptance/Remove-RecoveryFixture.ps1`、`infra/acceptance/README.md` | `infra/compose.yaml`、`infra/compose.app.yaml`、`infra/docker/application-docker.yml`；这些共享运行配置不为验收改写 |
| 3：评论恢复 | `infra/acceptance/Invoke-CommentRecoveryAcceptance.ps1`、`xiaohashu-comment/xiaohashu-comment-biz/src/test/java/com/quanxiaoha/xiaohashu/comment/biz/CommentRecoveryProbe.java` | `CommentOutboxRelay`、`CommentInboxProcessor`、`Comment2DBConsumer`、`infra/COMMENT-DELIVERY-RECOVERY.md` |
| 4：Canal 恢复 | `infra/acceptance/Invoke-CanalRecoveryAcceptance.ps1` | `xiaohashu-search/xiaohashu-search-biz/src/main/java/com/quanxiaoha/xiaohashu/search/biz/canal/CanalClient.java`、同目录 `CanalSchedule.java`、`service/DocumentIndexRefreshService.java` |
| 6：状态交付 | 无新增产品代码 | `docs/deployment/tutorial-status.md`、`docs/deployment/local-verification.md`、`infra/COMMENT-DELIVERY-RECOVERY.md`、`infra/README.md` |

执行顺序：2 → 独立环境 → 3 → 4 → 6。评论与搜索环境分阶段启动，控制内存峰值。每阶段失败时先保存证据、恢复本阶段资源，再修复并重跑该场景。

运行证据放在 Git 忽略的 `infra/.runtime/acceptance/<RunId>/`；不得把运行凭据、消息 payload 或用户手机号提交进仓库。

## 四、任务 2：全量计数审计

**交付物：** 一次完整历史实体快照审计、异步队列水位记录、稳定差异清单与复查结果。审计脚本没有写回计数参数。

**接口：**

```powershell
$projectRoot = 'F:\code\xiaohashu\xiaohashu'
$runId = 'accept-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
./infra/Invoke-FullCountAudit.ps1 -RunId $runId
# 输出：infra/.runtime/acceptance/<RunId>/count-audit.json
#       infra/.runtime/acceptance/<RunId>/count-mismatches.csv
#       infra/.runtime/acceptance/<RunId>/count-watermarks.json
# 退出码：0=完整审计通过；2=稳定差异；3=异步积压/窗口不稳定；1=执行失败。
```

### 2.1 固定口径并覆盖全部实体

- [x] 对照当前 Mapper 确定下面八项口径：既有七项对账指标，加上 `t_note_count.comment_total`。

| metric | 实体/存储列 | source_count |
| --- | --- | --- |
| FOLLOWING | 用户 / `following_total` | `t_following WHERE user_id = 实体ID` 的行数 |
| FANS | 用户 / `fans_total` | `t_following WHERE following_user_id = 实体ID` 的行数 |
| USER_LIKE | 用户 / `like_total` | `t_note_like l JOIN t_note n ON n.id=l.note_id WHERE n.creator_id=实体ID AND l.status=1` |
| USER_COLLECT | 用户 / `collect_total` | `t_note_collection c JOIN t_note n ON n.id=c.note_id WHERE n.creator_id=实体ID AND c.status=1` |
| NOTE_PUBLISH | 用户 / `note_total` | `t_note WHERE creator_id=实体ID AND status<>2` |
| NOTE_LIKE | 笔记 / `like_total` | `t_note_like WHERE note_id=实体ID AND status=1` |
| NOTE_COLLECT | 笔记 / `collect_total` | `t_note_collection WHERE note_id=实体ID AND status=1` |
| NOTE_COMMENT | 笔记 / `comment_total` | `t_comment WHERE note_id=实体ID` 的现存行数，包含回复 |

- [x] 用户全集取 `t_user.id`、`t_user_count.user_id`、`t_note.creator_id`、`t_following.user_id/following_user_id` 的并集。笔记全集取 `t_note.id`、`t_note_count.note_id`、点赞/收藏/评论源表 `note_id` 的并集。不得只遍历日增量标记表或当前有计数行的实体。
- [x] 作者获赞/获收藏沿用当前 Mapper 的笔记状态口径，不额外加“只看公开笔记”等过滤。如果产品口径有争议，在报告单列，不暗中调整预期值。
- [x] 单个 MySQL 连接内按下面事务边界读取实体、聚合源值及存储值，避免多个连接读取不同时刻的数据：

```sql
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
-- 本文件中的实体全集、八项聚合、孤立实体检查均在本事务内执行。
COMMIT;
```

- [x] 查询结果固定包含 `metric, entity_id, source_count, stored_count, counter_row_present, status`；64 位 ID 写成字符串。没有计数行但源值为 0 标记 `ZERO_ROW_ABSENT`，允许懒初始化；源值大于 0 且无计数行为 `MISSING_COUNTER`；计数列为 NULL、负数、数值不同分别标记 `NULL_COUNTER`、`NEGATIVE_COUNTER`、`MISMATCH`。实体不存在但关系/计数仍在另列 `ORPHAN_ENTITY`，不忽略。
- [x] SQL 仅允许会话设置、只读事务、SELECT/CTE 与 COMMIT；脚本使用容器现有环境进行认证，禁止把密码拼到控制台命令输出。输出不包含用户资料与评论正文。

### 2.2 排除正常异步延迟

- [x] 审计前后记录相关 RocketMQ 消费组的主队列、重试队列 lag/inflight、匹配 DLQ 数，以及笔记计数 outbox、评论 outbox/inbox 的未完成数量。消费组从当前 `MQConstants` 和消费者注解枚举，报告列出实际覆盖组名。
- [x] 有积压时按 5 秒间隔观察，最多等待 300 秒；不重置消费位点、不清消息。未清空则返回退出码 3，并保留本轮快照。
- [x] 在水位稳定时取得第一份快照；等待 10 秒后再次取完整快照，并再记录水位。两次均存在且源值不变的差异列为稳定差异。窗口内有持续写入或水位变化的实体标记为需复查，不能据此宣布“全库一致”。
- [x] 在独立环境创建四种固定用例校验审计器：正确计数、有源值但缺计数行、没有标记的历史错误计数、源值为 0 的懒初始化实体；另覆盖 NULL/负数和无主实体。用例应分别得到上文定义的状态。共享库不造错数。
- [x] 报告列出扫描用户数、笔记数、八项检查行数、稳定差异、未决实体、孤立实体和异步水位。仅当覆盖完整、窗口稳定且没有异常时标为通过。发现差异后先形成逐实体修复清单；本任务不批量回写。

**任务 2 执行结果（2026-10-19）：**

- 隔离审计器自测 `accept-1605a3e9c876` 通过 19 项断言；覆盖 8 项指标的 `MATCH`、`ZERO_ROW_ABSENT`、`MISSING_COUNTER`、`NULL_COUNTER`、`NEGATIVE_COUNTER`、`MISMATCH`、`ORPHAN_ENTITY`，并验证 outbox 空水位、消费重试/inflight 与 DLQ offset 解析。
- 共享库只读审计 `accept-64a79d8159bf`：用户实体并集 3（其中 1 个孤立计数实体）、笔记 109、审计行 342；两次快照一致，稳定差异 6 行，没有窗口不稳定项。审计状态为 `stable-differences`，返回码 2，不代表全库计数一致。
- 八项计数相关的 20 个消费者组：主队列 lag/inflight、重试队列 lag/inflight、匹配 DLQ 数均为 0。`t_note_operate_outbox` 为空；评论 outbox 为 3000 条 `SENT`，Inbox 为 2001 条 `APPLIED`，未发现未完成项。另有离线旧组 `xiaohashu_group` lag 20，不属于这 20 个计数链路组，保留记录，不清消息或位点。
- 稳定差异清单在忽略目录 `infra/.runtime/acceptance/accept-64a79d8159bf/count-mismatches.csv`：用户实体 `100` 的 FANS 源值为 1、存储值为 0；孤立的 `t_user_count` 实体 `672148930` 有 5 个孤立计数指标。没有改写 MySQL 或 RocketMQ 状态；后续是否逐条修复需先确认这些历史数据的来源。

**计数差异修复与复审（2026-10-19 19:01 +08）：**

- 用户要求修复全量审计中的差异后，重新核对当前源数据：用户 3 → 用户 100 的关注关系存在，用户 3 的 `following_total=1`，用户 100 的 `fans_total=0`；相关队列无积压。单事务锁住用户 100 计数行后，将 `fans_total` 按 `t_following` 的源值从 0 修正为 1。
- `672148930` 在 `t_user` 不存在，且在关注关系、笔记、点赞、收藏、评论源表均无引用。其 `t_user_count` 只有 `following_total=1`，其余四项为 0；满足逐项核验条件后，仅删除此孤儿计数行。没有 Redis 计数缓存键，也没有更改消息位点。
- 完整只读复审 `accept-117a28e3e111` 返回 0：2 个用户实体、109 篇笔记、337 条检查、八项计数指标、稳定差异 0、未稳定行 0。20 个相关消费组主队列/重试队列/inflight/DLQ 均为 0。旧通用消费组仍有 20 条不属于这些计数链路的积压，未触碰。
- 脱敏报告及逐条修复摘要保存在忽略目录 `infra/.runtime/acceptance/accept-117a28e3e111/`。

**完成条件：** 共享数据取得完整报告；审计器在隔离错数用例下能检出未标记差异；报告没有将懒初始化误判成错数，也没有自动修改业务计数。

**提交：** `feat(audit): add full historical count verification`，中文正文写明覆盖的八项口径、审计结果和只读边界。

## 五、任务 3/4 的前置：独立业务验收环境

**交付物：** 能运行真实评论/搜索业务链路、可重启且数据保留的专用环境。启动前预检失败时不触碰共享环境。

**接口与资源清单：**

```powershell
./infra/acceptance/Prepare-RecoveryFixture.ps1 -RunId $runId -Stage Comment
./infra/acceptance/Prepare-RecoveryFixture.ps1 -RunId $runId -Stage Canal
./infra/acceptance/Remove-RecoveryFixture.ps1 -RunId $runId
# manifest: infra/.runtime/acceptance/<RunId>/manifest.json
# Docker 项目名：xiaohashu-<RunId>
# 必须标签：codex.acceptance.run=<RunId>、codex.acceptance.project=xiaohashu
```

- [ ] 独立配置复用现有镜像版本，启动本阶段所需真实依赖。评论阶段包含 MySQL、Redis、RocketMQ namesrv/broker、Cassandra、KV、comment，以及代码实际需要的用户/ID 服务与发现依赖；搜索阶段包含 MySQL、Redis、Canal、ES、search 及其实际 RPC 依赖。初始化时核对真实调用依赖，缺少依赖则预检失败，不用 mock 替代正文持久化、MQ 投递、Inbox/Outbox、计数或 ES 写入。
- [ ] 同阶段服务在专用网络内用服务名寻址。仅必要的 HTTP 验收入口以 `127.0.0.1` 的动态宿主机端口发布，实际端口从 Docker inspect 解析后写入 manifest。
- [ ] manifest 固定字段为 `schemaVersion=1`、`runId`、`projectName`、`userId`、`noteId`、`commentBaseUrl`、`searchBaseUrl`、`containers`、`networks`、`volumes`、`testEvents`；尚未创建的资源记 `planned`，成功后记实际 ID 和 `created`。每次写入先保存临时文件，再单文件替换；重启和清理均从 manifest 读取，不能凭名称前缀猜资源归属。
- [ ] MySQL 以当前库的 schema-only 导出和已提交迁移为准；导出不包含历史用户、笔记或消息正文。新建随机测试用户和笔记作为父级依赖，记录全部 ID。Cassandra 使用当前 keyspace/table 结构及 25.16 围栏迁移；ES 使用 `database/es/` 的现有 mapping。隔离种子不是正式演示数据。
- [ ] 新环境密码在运行期生成，仅保存在被忽略的本次运行目录。应用配置指向专用 MySQL/Redis/MQ/Cassandra/ES/Canal，Nacos 注册也指向独立服务；不读取共享发现实例，禁用与本轮无关的定时任务。
- [ ] Broker 的通告地址必须在本次网络内可达；重试和 DLQ 使用隔离 Broker 的原有消费组名，不改成共享 Broker 地址。Canal 连接隔离 MySQL，独立 server-id 与位点目录。
- [ ] 故障操作先 inspect 验证项目和 RunId 标签，且确认容器不属于共享 `xiaohashu` 项目；所有 stop/start/restart/remove 只接受 manifest 中单个容器 ID。
- [ ] Broker/Cassandra/MySQL 持久数据写在本次运行目录或本次专属卷中；重启必须复用同一数据。先后两轮用不同 RunId，不能共享数据库、ES 索引、队列或消费位点。
- [ ] 记录共享栈启动前后的容器 ID、启动时间与健康状态。环境清理逐个处理已验证归属的容器/网络/专属卷；保留报告。宿主机目录不递归删除，若存在多文件目录需删除则交给用户手工处理。

**完成条件：** 本次环境健康、正常评论可完整落库、正常 MySQL 更新可进入 ES；共享容器 ID/启动时间不变。

**提交：** `test(infra): add isolated business recovery fixture`。

## 六、任务 3：评论故障恢复端到端验收

**交付物：** 真实评论从请求到 Outbox、RocketMQ、Inbox、Cassandra、MySQL、计数、缓存的状态证据，以及应用重启/重投后的幂等结果。

**接口：**

```powershell
./infra/acceptance/Invoke-CommentRecoveryAcceptance.ps1 -RunId $runId
# 输出 comment-recovery.json；全部断言通过才退出 0，否则退出 1。
# 恢复后单场景收敛超时 300 秒；B/C 场景故障保持 120 秒。
# F 的 Broker 重试到 DLQ 阶段单独计时，最多 4 小时；超时标失败并恢复环境。
# 仅针对标签匹配的独立环境操作；finally 恢复本场景停止的服务。
```

`CommentRecoveryProbe` 只放在测试源码，提供 `public static void main(String[] args)`；参数为 `--manifest`、`--operation`（`replay`、`prime-dlq`、`queue-status`）与 `--event-key`，运行退出码 0/1。以现有 RocketMQ SDK 为隔离 Broker 重投原始消息、查询重试/DLQ 和显式设置测试消息的重消费次数。它必须校验隔离端点、RunId 和 manifest；原始 payload 不写报告或控制台，不打入正式业务镜像。执行脚本先用 Maven `test-compile` 编译、准备本模块测试运行 classpath，并以本机现有 Java 镜像在本次专用网络运行 Probe，避免宿主机无法解析 Broker 通告地址的问题。

### 3.1 用状态和真实数据判定结果

- [ ] 用实际接口 `POST /comment/publish` 发随机正文，头 `userId` 为隔离用户。使用下面的请求构造方式，64 位 ID 全程保留十进制字符串；HTTP 200 后继续核对下游结果：

```powershell
$manifestPath = Join-Path $projectRoot "infra/.runtime/acceptance/$runId/manifest.json"
$fixture = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
$body = @{
    noteId = [string]$fixture.noteId
    content = "$runId-$([guid]::NewGuid().ToString('N'))"
} | ConvertTo-Json -Compress
$result = Invoke-RestMethod -Method Post `
    -Uri "$($fixture.commentBaseUrl)/comment/publish" `
    -Headers @{ userId = [string]$fixture.userId } `
    -ContentType 'application/json' -Body $body -TimeoutSec 30
if (-not $result.success) { throw 'Isolated comment publish was rejected.' }
```
- [ ] 每条测试评论记录稳定事件键、commentId、contentUuid、Outbox/Inbox 状态和 attempt_count、MySQL 行数、KV 正文一致性、笔记 comment_total 与评论列表结果。正文只记录 SHA-256 摘要，不输出原文。
- [ ] Outbox `SENT` 仅表示 Broker 接收；发布完成要求 Inbox `APPLIED`、评论元数据恰一行、正文匹配、计数只增加一次、评论列表可读。删除完成要求对应 Inbox `APPLIED`、元数据消失、正文清理完成、计数只扣一次。

### 3.2 逐个运行故障场景

| 场景 | 操作顺序 | 必须观察到的结果 |
| --- | --- | --- |
| A 正常基线 | 发布一条一级评论和一条回复，再删除 | 正文/元数据/列表一致，增减次数与真实评论行数一致 |
| B Broker 不可用 | 停独立 Broker；发布 10 条；保持 120 秒；重启 comment；恢复 Broker | 发送失败时 Outbox 保留 PENDING 或可回收的 SENDING；不能误标 SENT；同一持久记录在应用重启后恢复，最终全部落库且各计数一次 |
| C Cassandra 不可用 | 先确保消息可进 Inbox；停独立 Cassandra；发布 10 条；保持 120 秒；重启 comment 和 KV；恢复 Cassandra | Inbox 保留待重试状态且 attempt_count 增加；故障期间不标 APPLIED、不产生缺正文的成功评论；恢复后正文、元数据、计数和列表一致 |
| D 重复消息 | 对已 APPLIED 的发布与删除事件各额外投递 3 次，保留原事件键/原文 | 不增加评论行、不重复计数、不复活已删除评论；Inbox 去重记录仍在 |
| E 删除先于迟到发布 | 为同一随机 commentId 先投删除，再投原发布事件；另测重复删除 | 墓碑阻止复活；评论与正文最终不可读；计数不为负且无重复扣减 |
| F DLQ 恢复 | 在独立环境持有 Inbox 唯一事件行锁，测试 Probe 将一条真实消息设为接近消费重试上限后投递，使 Inbox 持久化超时；确认进入对应 DLQ；释放锁后重投原 topic 两次 | 必须真实观察 DLQ 和失败原因；不得手工写 DLQ 或跳改 APPLIED；恢复后只应用一次。若当前客户端不能控制重消费次数，则按真实重试周期等待并独立执行，不把模拟队列命名当作通过 |

- [ ] D/F 只重投本轮 manifest 中的原始事件，不重置共享或隔离消费组的历史位点。测试 Probe 保持 commentId、事件版本、UUID 与原始正文。
- [ ] C 场景故障期间每 5 秒记录一次状态，不通过手工修改状态/next_attempt_at 催成功；恢复阶段等待实际租约与退避到期。
- [ ] F 同时验证 `SENT` 但无 Inbox 的排查路径；消息已在 PENDING Inbox 时仅等待 Inbox 调度器，不再重投。
- [ ] 场景结束确认本轮没有未决 Outbox/Inbox、主队列/重试队列积压或未回收 DLQ；保留稳定去重键，不删除正式环境历史幂等记录。
- [ ] 复现代码缺陷时，在现有 `CommentOutboxRelayTest`、`CommentInboxProcessorTest` 等相关测试中添加能复现的回归，再做最小修复，并重跑失败场景。没有复现缺陷时不为了验收重写投递协议。

**完成条件：** A–F 全部取得运行证据；comment/KV 应用重启后仍能收敛；恢复后的正文、评论行、列表和计数一致；相同事件重复投递没有额外副作用。

**提交：** `test(comment): verify durable delivery outage recovery`；若包含修复，使用能说明具体缺陷的 `fix(comment)` 提交。

## 七、任务 4：Canal 调度器与搜索恢复验收

**交付物：** 实际 search 进程在连接失效后自行重连、重新消费 MySQL binlog，并把最新状态写入 ES。单独调用 `CanalClient.reconnect()` 不满足本任务。

**接口：**

```powershell
./infra/acceptance/Invoke-CanalRecoveryAcceptance.ps1 -RunId $runId
# 输出 canal-recovery.json；每阶段最多等待 180 秒，5 秒轮询。
# 记录 search 容器 ID/启动时间，必须证明是同一进程自行恢复。
```

### 4.1 正常更新与断线积压

- [ ] 在隔离 MySQL 建立随机用户、笔记及两张计数表行；通过真实 search 调度器确认初始文档进入 ES，直接 GET ES 与查询搜索 API 各核对一次。
- [ ] 保持 search 进程运行，停止本轮 Canal。确认 `CanalSchedule` 记录连接失效，并在约 5 秒退避后重试；不要由测试程序调用重连函数。
- [ ] 断线期间提交四类真实 MySQL 更新：`t_user` 昵称 A→B→C、`t_note` 标题 A→B→C、`t_user_count` 计数变化、`t_note_count` 计数变化；更新相应 update_time。另创建一条待删除的随机笔记，安排删除/不可索引状态事件。
- [ ] 恢复同一 Canal 容器及位点目录，等待日志出现 `Canal client reconnected; consumption will resume from the last acknowledged batch`；确认 search 容器 ID 和启动时间没有改变。
- [ ] ES 用户/笔记最终为 MySQL 最新版本 C，两类计数一致；删除或不可索引实体在 ES 为 404，搜索 API 不返回旧文档。不能只用日志“已重连”判成功。

### 4.2 ACK、回滚与持续恢复

- [ ] 保持 Canal 在线，停止本轮 ES 后写一批 MySQL 变更；确认 search 写 ES 失败后不把该批次当作成功推进。恢复 ES，观察未处理批次被重试并最终一致。
- [ ] 再次短暂停止/恢复 Canal，验证第二轮断线仍能恢复，不留下永久 `Broken pipe` 循环。保存脱敏日志时间和最新文档摘要。
- [ ] 查询独立 MySQL 当前 binlog 位置并观察本轮 Canal 消费进度，确认正常更新继续到达，不只恢复故障前的最后一条记录；不得手工改位点或删除 Canal metadata。
- [ ] 如果故障落在批次处理完成而 ACK 未完成窗口，允许同一批次重放；必须验证刷新是覆盖最新源状态，没有旧文档复活或重复副作用。可重复场景延续既有 `CanalScheduleRecoveryTest` 对 ACK 失败的精确覆盖；实际故障时未撞中该窗口应如实区分，不能宣布该特定窗口已做实测。
- [ ] 如出现失败，针对 `CanalClientReconnectTest`、`CanalScheduleRecoveryTest` 或文档刷新测试补充回归，修复后重跑整条 MySQL→Canal→search→ES 链路。

**完成条件：** 用户资料、笔记、两种计数表和删除路径在两轮断线后均收敛；同一 search 进程自动恢复；ES 失败恢复后批次没有丢失；没有重置任何共享位点。

**提交：** `test(search): verify Canal pipeline outage recovery`；实际修复另按缺陷使用 `fix(search)`。

## 八、任务 6：刷新交付状态与操作说明

**交付物：** 文档能够逐条对应实际证据，并准确说明仍存在的边界。

**本轮范围更新：** 用户要求先完成计数差异处理与文档状态更新；真实评论故障场景 C/F 和 Canal→ES 故障恢复因本机可用内存不足暂缓。隔离项目 `xiaohashu-accept-7ba05531ecfe` 的 14 个容器当前均停止。文档记录已更新为当前证据，不声称这两类场景通过；依赖实际 F 结果的 DLQ 操作顺序说明保持未完成。

- [x] 在 `docs/deployment/local-verification.md` 追加计数复审章节，记录 RunId、时间、命令退出码、预期/实际结果、运行证据路径及本次没有启动容器；本次没有构建镜像。
- [x] 更新 `docs/deployment/tutorial-status.md`：记录八项全量审计覆盖数与修复结果；评论 A/B/D/E 的既有结果和 C/F 未执行；Canal 客户端重启探测与真实业务链路验收的区别；未复现的 ACK 窗口；评论/Canal 验收因内存不足暂缓。没有把隔离验收外推成共享环境停机验收。
- [x] 将 auth 未注入凭据、未发短信记录保留为历史状态，追加最新事实：短信接口 HTTP 200/业务成功、阿里云业务码 OK、用户确认手机收到。未记录真实号码、验证码、请求 payload 或密钥。
- [x] 保留验证码日志问题为独立未处理项，避免把用户“手机收到”解释成日志问题已经修复。FRP 公网验收继续延期；MySQL/Cassandra 是最终一致，Cassandra 永久丢失依赖备份，这些边界继续明确。
- [ ] 用实际 F 场景结果更新 `infra/COMMENT-DELIVERY-RECOVERY.md` 的 DLQ 操作顺序；在 `infra/README.md` 链接验收入口，注明只操作本次标签匹配资源。
- [x] 自审“仍未”“已完成”“没有发短信”措辞，区分历史记录与当前结论；未完成项写清原因和下一步。
- [x] 核对本次 Git diff 不含凭据、号码或原始消息正文。此轮只读审计并修复两条 MySQL 计数数据，没有为故障验收创建资源，也没有停止/重启共享容器；独立验收项目保持停止。

**提交：** `docs(verification): record remaining acceptance results`，中文正文列出实际通过项、未决项与用户确认短信送达。

## 九、统一验收与执行收尾

只运行本轮变更对应的必要检查；没有 Java 改动时不重复整仓测试。修改实现后，先运行相关回归，再执行受影响模块编译及本计划真实场景。

```powershell
$env:JAVA_HOME = 'D:\java\jdk-17.0.18+8'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:MSYS_NO_PATHCONV = '1'
mvn -pl xiaohashu-comment/xiaohashu-comment-biz,xiaohashu-search/xiaohashu-search-biz,xiaohashu-data-align -am -DskipTests compile
```

- [x] 任务 2：完整审计有真实报告；先修复已核实的 6 条稳定差异，再复审通过；最终报告退出码为 0。
- [ ] 任务 3：A–F 均有实际持久状态与业务结果，失败修复后复跑；没有共享资源被故障操作影响。
- [ ] 任务 4：同一 search 进程自动重连后，真实 MySQL 更新进入 ES，搜索 API 返回最新结果。
- [x] 任务 6：当前范围内的最新文档与实际证据一致，短信已收到、FRP 待服务器、验证码日志待修及内存不足导致的评论/Canal 验收延期均准确记录；依赖 F 实测的操作顺序仍待后续。
- [ ] 提交只包含本任务明确文件；原有三个本地文件和根 `.evn` 未被暂存或改动。
- [ ] 最终回复列明每项结果、提交和剩余阻碍；不以编译成功、接口 200、Broker SEND_OK 或日志重连成功替代完整业务验收。

**后续执行方式：** 用户要求开始后，在当前会话按上述顺序执行并逐项更新复选框。本轮到计划文档交付为止。

## 十、2026-10-19 部署与服务级复验补充

用户随后授权继续完成镜像部署、服务联调、审计频率决定和提交。共享基础设施保持运行；仅逐个替换本地应用服务容器。

- [x] 构建并部署 12 个后端服务镜像；修复 data-align 的 `StringRedisTemplate` 按名称注入问题，单测及完整模块测试通过，部署后应用健康检查通过。
- [x] 通过 Docker 网络内 HTTP 验证活动笔记详情与搜索结果。
- [x] 对随机评论执行真实发布、inbox 持久化、列表读取、评论计数递增、删除、计数回滚和缓存失效；测试评论已清除，持久幂等记录保留。
- [x] 全历史计数审计决定每月人工只读执行（建议每月 19 日），不设本地自动修复/无人值守扫描任务；首次后续复核建议为 2026-11-19。
- [ ] 评论/Cassandra 故障注入与 Canal→ES 完整恢复仍待本机有足够内存时执行；FRP 公网访问仍待用户准备服务器。
- [x] 提交本轮及先前积累的已审核工作区改动；提交时排除 `.idea/encodings.xml`、`AGENTS.md`、`HANDOFF-CODEX.md`、根 `.evn` 和运行时凭据。
