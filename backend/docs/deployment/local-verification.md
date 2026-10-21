# 完整应用本地运行验收

日期：2026-10-18。部署范围：当前 Windows 主机已有数据与镜像，Docker Desktop 分配约 8 GiB 内存。前端使用 `F:\code\xiaohashu\xiaohashu-vue3` 原版 Vue 源码。

## 运行结果

- 显式使用 `D:\java\jdk-17.0.18+8` 执行全项目 `mvn -DskipTests package`，36/36 模块成功，退出码 0；本轮接口实测单独执行，没有将跳过单元测试写成单元测试通过。
- 原版前端执行 `npm run build` 成功（160 个模块），重新构建镜像时实际执行 `nginx -t` 通过；已更新运行中的前端容器，HTTP 200。
- Compose 项目名 `xiaohashu`：原版前端、12 个 Java 服务及基础设施共 27 个持续运行的容器；配置健康检查的容器均为 `healthy`。`minio-init` 是另一个一次性容器，正常退出码为 0。
- `http://localhost:8088` 原版前端返回 HTTP 200，浏览器实际显示频道及现有的三条公开笔记。
- `http://localhost:18000` 经本机 FRPS/FRPC 转发到同一前端，返回 HTTP 200。公网穿透按用户安排，等有服务器后再验收。
- 按用户要求移除了 16 个已停止的小哈书旧容器（未加 `-v`）；所有 Compose 服务仍正常运行，MySQL 命名卷和 `F:/docker` 数据目录都保留。未删除数据库、Redis、Cassandra、MinIO 数据。
- `infra/Start-Local.ps1 -SkipBuild` 本机实际运行成功，包含配置准备、校验、Compose 启动及等待健康检查。

## 已走通的真实接口链路

验收使用随机手机号、密码、标题及临时 PNG；验证码仅由本机管理员写入 Redis，不调用短信云服务。

| 检查 | 结果 |
| --- | --- |
| 前端 Nginx → 网关 → 认证服务，验证码登录及自动注册 | 通过，实际调用用户服务及 ID 生成服务 |
| 修改密码，再用密码登录 | 通过 |
| 用户主页及计数服务调用 | 通过，Docker 环境关闭随机异常演示 |
| 带登录令牌上传 multipart 图片至 OSS → MinIO | 通过，宿主机访问返回的图片 URL 为 200 |
| 发布笔记 → MySQL 元数据 → MQ 消费 → KV/Cassandra 正文 | 通过；等待正文消费者完成后查询详情并核对标题、正文 |
| 原前端个人页笔记列表适配 | 通过，新笔记可查，Snowflake ID 返回字符串 |
| 前端搜索路由 → 搜索服务 | 通过，接口正常响应；本项未将响应成功作为新笔记已完成 ES 索引的证明 |
| 带登录令牌删除笔记 | 通过 |
| 无令牌但伪造 `userId` 请求受保护接口 | 被拒绝，错误码 401 |

完整脚本本机输出 `LOCAL_APP_SMOKE_PASSED`，退出码 0，日志留在 Git 忽略的 `target/local-app-smoke.log`。结束时删除本轮 MySQL、Redis、Cassandra、MinIO 临时数据及 PNG；再次查询随机测试笔记和用户的残留数量均为 0。

## 本轮发现并处理的部署问题

首次请求某个下游服务时，Gateway 的服务发现使执行线程切换到 `boundedElastic`；Sa-Token 1.38 的同步上下文使用 ThreadLocal，教程过滤器裸读登录身份时可能取不到上下文，并将异常当作匿名请求放行。实际观察到发布 DTO 的 `creatorId=null`，MySQL 插入失败，事务消息返回 `ROLLBACK_MESSAGE`，但发布接口仍报告成功。

为使本地整组应用可用，新增仅在 `docker` profile 生效的 `DockerLoginContextFilter`，显式绑定当前 `ServerWebExchange` 读取身份后立即清理，移除外来 `userId` 并设置认证所得 ID；Docker 下停用旧过滤器，普通 dev 环境仍保留原实现。重新构建并重建网关后，从冷服务发现开始重复上述流程，发布、正文查询及删除全部通过。

当时记录的“事务回滚时发布接口仍返回成功”已在后续修复：`NoteServiceImpl.publishNote` 现在只在 RocketMQ 本地事务状态为 `COMMIT_MESSAGE` 时返回成功，其他状态返回 `NOTE_PUBLISH_FAIL`；对应回归测试覆盖 `UNKNOWN` 状态。正文异步可见期间缓存空正文的问题也已在后续修复，并在本文最新复验章节实测发布后立即可读。

## 体验说明

真实阿里云短信凭据未注入 Docker。使用 [本地验证码脚本](../../infra/Get-LocalLoginCode.ps1) 后，在前端直接填写手机号与验证码登录，勿点击发送按钮覆盖该临时码。验证码有效期 180 秒，首次登录自动注册的账户由用户自行保留使用。

已有三条笔记的部分图片、视频是历史远程地址，存在失效资源；本次没有改写这些历史数据。本段记录的是最初部署验收时的功能状态；后续评论缓存、笔记 ID 和计数审计改动，以及当前未验收边界，见下方最新复验和 [tutorial-status.md](tutorial-status.md)。

## 后续可靠性改造说明

上面的运行结果记录于本轮 outbox/inbox、笔记正文时序、计数审计和搜索刷新改造之前。当前工作区已补入这些实现并通过相应 Java 单测；Docker Desktop Linux 引擎不可连接时未能重新构建镜像、执行新评论 DDL 或跑 MySQL/RocketMQ/Redis/Cassandra/Canal/ES 联合验证。不要把本页历史验收当作这些后续改动的运行验收。

## 2026-10-19 可靠性改造复验

- 使用 JDK 17 运行相关模块测试。首次完整运行在 `xiaohashu-count-biz` 的 `MQTests.testBatchSendMQ` 失败：该压测从宿主机发送 1620 条消息，Broker 通告的 `rocketmq-broker:10911` 仅在 Docker 网络内可解析。该用例会向真实队列写数据，因此没有改地址后重发。排除该单个压测后，count、data-align、search、comment 模块测试 `BUILD SUCCESS`；前一轮同一 Reactor 中 KV、note、OSS 模块测试均通过。
- `infra/Build-Images.ps1` 成功构建 12 个后端镜像。前端镜像在运行时 DNS 解析修正后通过镜像内 `nginx -t`，Compose 已用新镜像重建应用。
- `infra/Start-Local.ps1 -SkipBuild` 完成配置检查、Compose 更新和等待健康状态；当时整组应用与基础设施的健康检查均为 healthy。
- 评论 outbox、inbox、tombstone、count-event 四张表已存在于当前 `xiaohashu` 数据库。此处是查询确认，不是本轮重新执行建表迁移。
- 五个只读计数审计任务（XXL-JOB ID 5–9）最近一次手动执行结果均为 trigger/handle `200/200`。旧的标记表清理任务（ID 2）已启用，并在新 data-align 容器中手动触发，结果 `200/200`。运行时共查到 63 张计数标记表，日期为 2026-10-17 至 2026-10-19；没有过期表。该任务会先审计过期表，发现差异或审计异常则保留表。
- 通过 `http://localhost:8088/media/xiaohashu-local/<随机对象>` 实测 GET `200`、HEAD `200` 和 Range `206`，并核对正文、Content-Length 和部分响应内容。测试用 MinIO 对象已删除。
- 评论服务修正热榜 Lua 参数序列化后，仅重建并重启了评论容器；容器健康检查为 `healthy`。回归测试按预期在修复前失败、修复后通过。将 MySQL 中两条明确的 `CACHE_INVALIDATE` 待重试记录（ID 2、4）提前到当前时间后，服务实际消费成功，状态均为 `APPLIED`、attempt 为 16、last_error 为 NULL；对应 Redis generation 为 2，热榜 ZSET 和 ready key 均已失效。
- 最终复核补上热门榜跨实例重建锁、旧版删除事件的持久墓碑，以及终态消息正文保留周期后，评论模块完整套件为 73 项通过。重新打包评论服务、构建 `xiaohashu/comment:local` 并只重建评论容器后，健康检查为 `healthy`。
- 在真实 Redis 中用随机测试键执行 Lua：持有旧 token 的重建被拒绝，当前 owner 能替换 ZSET，错误 owner 不能释放锁，正确 owner 可以释放；所有测试键和临时脚本均已清理。对两条 MySQL 有界清理语句执行只读 `EXPLAIN`，确认语法与索引计划有效，没有改写业务行。

本轮仍未对共享 Broker/Cassandra 做中断故障注入，也没有压测热门评论跨实例并发重建；FRP 只有本机转发验证，公网访问需等用户有服务器后进行。Canal→ES、热门评论 499/500/501 项边界、正文即时可见及演示数据验证结果见下方最新复验记录。

## 2026-10-19 最新运行复验与演示数据

- 搜索服务曾在 Canal 连接断开后持续出现 `Broken pipe`，且调度线程只回滚失效连接，没有建立新连接。已实现失效连接清理、ACK 失败重连和 5 秒退避；JDK 17 搜索模块完整测试 14 项通过。没有在共享容器中人为断开 Canal，实际断线恢复由单测覆盖。
- 对本地用户 100 通过用户服务临时修改昵称，确认 ES 用户文档及搜索接口都返回新昵称；随后恢复原昵称，并确认 ES 文档也恢复。验收没有留下用户资料变更。
- 用业务接口发布并搜索笔记 `2111994861883256866`，确认文档进入 ES。再为该笔记发布 10 条评论，搜索接口返回 `commentTotal=10`，确认笔记计数变更触发了后续 ES 刷新。
- 演示集第 1 篇笔记 `2111996696773816352` 发布成功后、开始处理下一篇前，立即调用详情接口，返回了 Cassandra 正文；评论列表接口返回 10 条可读评论。首篇和末篇都可由搜索接口查到，末篇 `2111998137387216925` 的 ES 评论数为 10。
- 已通过 [本地演示数据生成脚本](../../infra/Seed-DemoContent.ps1) 经笔记、评论业务 API 建立 99 篇、990 条评论；另有一篇早先的 smoke 笔记及 1 条评论，因此目标演示数据合计 100 篇、991 条评论。Git 忽略的 `infra/.runtime/demo-content-demo-20261019.json` 记录了 99 篇批量数据以及 smoke 笔记、评论 ID；另记录一篇零评论的早期 API 探测笔记，供后续选择性清理，不计入演示数据。MySQL 按 `LOCALDEMO` 前缀查到 101 篇笔记、991 条评论，其中 1 篇是上述零评论探测记录。脚本现已改为在每次 API 调用前记录笔记/评论标记，并在每条评论持久化后立即补记 ID；若执行中断，manifest 里的笔记 ID、标题及 pending attempt 可用于核查该笔记已写入的评论 ID。
- 发现页第 1 页和第 10 页各返回 10 条笔记；评论分页、正文、搜索均已用容器内真实 HTTP 请求验证。评论模块全套 84 项、搜索模块 14 项测试通过（含模拟 RocketMQ 发送抛错后安排 outbox 重试且不标记 SENT、Canal ACK 失败失效连接以及重连退避测试）。评论缓存的有效空页按缓存命中返回，不再为此反复回源。
- 热门评论 Lua 真实 Redis 边界复验：499 项、500 项及第 501 个候选处理后缓存不超过 500 项；500 项时更新已有成员不会误删，优先级排序正确。临时 Redis 键已逐个清理。
- 完成后再次查询 Compose，所有配置了 healthcheck 的应用及基础设施容器均为 healthy；FRPC/FRPS 显示运行中，但本身没有 Compose healthcheck。FRP 公网/VPS 访问验收按用户要求继续待做；计数自动写回仍保持关闭，因为当前缺少源事件版本与 MQ 消费水位协议。MySQL 与 Cassandra 依靠幂等、持久化重试保持最终一致，仍非跨库原子事务。

## 2026-10-19 后续边界复核

- 评论 inbox 的 KV 正文写入失败补充了单测：模拟 Cassandra RPC 抛错，确认 inbox 安排重试，评论元数据不落库、不标记 `APPLIED`，也不提前创建 outbox 后续事件。JDK 17 执行 `CommentInboxProcessorTest` 共 13 项通过。现有 outbox 发送失败测试也覆盖“不标记 SENT 并安排重试”。共享 RocketMQ/Cassandra/Canal 仍未做中断注入，避免影响正在运行的本地服务。
- 前一轮先用两个并发 Redis 客户端确认重建锁只有一个 owner；完整双 `CommentServiceImpl` 实例快照重建结果见下方“计数与依赖恢复复验”。
- 前一轮只读复核发现用户 100 的 `t_note(status=1)` 为 52、`t_user_count.note_total` 为 49；当时没有共同水位所以先未回写。随后补充 outbox/幂等表、共享用户锁并确认 MQ lag/inflight 为 0，最终单用户纠偏结果见下方。
- 早期零评论 API 探针笔记 `2111994564884590618` 在 MySQL 中状态为已软删除、评论数为 0；Elasticsearch 查询该笔记返回 HTTP 404。它已不出现在活动列表和搜索索引，无需再物理删除保留审计记录。

## 2026-10-19 计数与依赖恢复复验

- 为笔记发布数新增 `database/25.13-note-publish-count-reliability.sql`：MySQL outbox 与笔记插入/软删除同事务；note 写入、count 消费和纠偏使用同一用户锁；count 事件按稳定事件 ID 去重。旧版消息也使用稳定的兼容键。只有锁成功且操作类型与增减量匹配时才更新计数。
- 迁移应用到当前 `xiaohashu` 库后，先更新并重建 `xiaohashu/count:local`，确认健康，再重建 `xiaohashu/note:local`；两个容器均为 `healthy`。Compose 其余共享容器未重启。
- 纠偏前通过 RocketMQ 管理命令确认 `NoteOperateTopic` 四个队列 lag/inflight 都为 0，对应 `%RETRY%` 队列 lag/inflight 为 0，topic 列表中没有 `xiaohashu_group_NoteOperateTopic` 的 DLQ。随后执行单用户锁保护脚本，用户 100 的 `t_user_count.note_total` 从 49 校准为 `t_note(status=1)` 的 52；SQL 回执为 `stored_before=49, source_count=52, stored_after=52`。其他计数任务仍保持只读审计。
- 校准后查询确认源表和计数表都为 52，outbox 没有 PENDING/SENDING 项，计数事件表没有本轮测试残留。`25.13` 新表已存在；`25.14` 不会随服务启动自动执行。
- 计数对账已扩展为七项并具有锁保护写回：关注、粉丝、笔记点赞、笔记收藏、作者获赞、作者获收藏、作者发布。主计数消费者从已提交 MySQL 源关系重算绝对值、提交后删 Redis 计数缓存；源服务改为同步发送计数事件，失败沿源 RocketMQ 消息重试。遗留 `*2DB` 队列同样重算绝对值，以免升级期间的旧 delta 重复入库。对账只修复日增量表标记的实体，不扫描所有历史实体。
- JDK 17 目标测试共 44 项通过：count 8 项、note 29 项、RocketMQ 客户端恢复 1 项、Cassandra 客户端恢复 1 项、Canal 客户端恢复 1 项、真实 Redis 双实例热评重建 1 项、Canal 调度恢复 3 项。集成探测使用隔离关闭端口验证客户端失败，再连接健康依赖；RocketMQ 只查询已有 topic 路由，Cassandra 只读 `system.local`，Canal 使用专属 client ID 且不拉取或确认批次，Redis 使用随机键并逐个清理。
- 两个独立 `CommentServiceImpl` 实例连接真实 Redis，同一随机笔记并发触发完整热评快照重建。阻塞第一个实例的数据库读取后启动第二个实例，结果只有第一个实例执行 SQL，ZSET 与 ready marker 正确生成，锁被安全释放。

## 2026-10-19 笔记正文跨存储恢复复验

- 新增并应用 `database/25.15-note-content-saga.sql`，MySQL 中存在 `t_note_content_saga`；随后使用 JDK 17 编译打包 note 服务，重建并仅重启 note 容器，容器健康检查通过。
- 用随机 note ID 和 UUID 在 MySQL 建立隔离测试行，通过 Docker 网络调用真实 note 编辑接口。MySQL 正文 UUID 指针完成条件更新，紧接着调用详情接口，实际读回本次随机正文；后台恢复任务把删除旧正文的 saga 收敛到 `COMPLETED`。
- 再用单行锁让两次编辑都读取相同旧 UUID 后同时提交，结果恰有一个编辑成功，另一个返回 `NOTE_UPDATE_FAIL` 并由恢复任务补偿；详情保留胜者正文，两个 saga 分别收敛为 `COMPLETED` 和 `ROLLED_BACK`。
- 对已有 Cassandra 正文调用清空编辑接口后，MySQL `content_uuid` 变为 `NULL`、`is_content_empty` 为 1，清理 saga 完成且 KV 查询确认旧正文不存在。
- 测完通过 KV 服务逐个删除旧、新测试正文，并删除该随机 note ID 对应的 MySQL note/saga 行；再次查询确认两行数均为 0，KV 查询确认测试正文不存在。
- 笔记模块完整 Maven 测试共 82 项，0 失败、0 错误、1 项既有环境集成测试按条件跳过。包含 saga 状态迁移、异常恢复、更新 UUID CAS、并发编辑补偿及 mapper 配置检查。
- 新增正文更新使用 MySQL compare-and-set 防止两个编辑并发时后提交者误覆盖正文指针；提交指针前锁定 saga 租约，租约过期后返回的 Cassandra 写会以独立补偿记录重新排队。恢复清空操作时会包含软删除笔记核对正文引用；补偿任务执行删除前也会再核对 MySQL 指针。共享 Cassandra/MySQL 服务没有被停机注入故障；服务恢复期间真实长时间重试和 MySQL/Cassandra 同时断开的场景仍应在隔离测试栈验收。Cassandra 已完成数据的永久丢失不由已清除载荷的 saga 恢复，需要备份。

此前只做了隔离端口恢复探测，没有重启服务容器；本页后续“计数、压力与依赖中断复验”记录了本轮在独立容器中的实际重启测试。共享服务仍未被中断，FRP 公网验收仍等用户有服务器后继续。

## 2026-10-19 计数、压力与依赖中断复验

- 七项计数审计任务均已注册到数据对齐服务。针对本地关注计数发现的实际差异（用户 3 源关系数 1、计数表 0），只为该实体写入一个当天标记并手动触发审计；日志记录 `entities=1, mismatches=1, repaired=1`，随后源值与存储值均为 1。不会把本次结果外推为全库扫描。
- 通过真实 user-relation 接口以随机用户 ID 关注、取消关注用户 3：关系源表和关注/粉丝计数分别从 0 到 1 再回到 0。测试用户计数行、测试产生的当天标记及 Redis 键均已按具体 key/行清理；用户 3 的原有关注关系与计数仍相符。
- 评论热评重建在真实 Redis 上以 128 个独立服务实例同时竞争同一随机笔记锁；只有一个实例执行数据库快照读取，最终 ZSET/ready 状态正确。旧版双实例用例也通过；两次用到的随机 Redis key 均在 finally 中清理。
- 新建带 `codex.test.fixture=dependency-recovery` 标签的隔离 RocketMQ、Cassandra、Canal 容器，以不同端口运行且不挂载项目数据目录。实测 Broker 停机时生产发送失败、同一生产者在 Broker 重启后发送成功；Cassandra 既有驱动会话在节点重启后恢复查询；Canal 客户端在服务重启后重新连接。所有隔离容器、隔离网络和临时 Broker 配置已逐个清理；共享基础设施未被停止或重启。
- JDK 17 对计数、笔记、关系、数据对齐、搜索、KV、评论、认证模块运行完整 Maven 测试：88 个测试套件、278 项，失败 0、错误 0、条件跳过 13 项。另单独运行真实 Redis 128 路竞争、RocketMQ Broker 重启、Cassandra 节点重启、Canal 服务重启四项 opt-in 集成测试，全部通过。
- `infra/compose.app.yaml` 已映射 `ALIYUN_ACCESS_KEY_ID` 与 `ALIYUN_ACCESS_KEY_SECRET`，Aliyun 短信日志脱敏单测通过。当前运行中的 auth 容器里两个变量均为空，因此没有发起收费云端短信；真实发送仍需在本机运行环境注入凭据并指定本人可接收的测试号码。
- 复核结束时所有共享应用与基础设施容器仍 healthy；FRPC/FRPS 正常运行。FRP 公网访问仍等待用户准备服务器。

## 2026-10-19 迟到正文写入围栏复验

- 新增并在当前 Cassandra 应用 `database/25.16-note-content-delete-fence.cql`，给 `note_content` 增加 `is_deleted`。新版 KV 使用 LWT `INSERT IF NOT EXISTS` 保存正文，用 LWT 插入或更新删除围栏；同一 UUID 的迟到保存不能越过已完成删除。
- 新增随机 UUID 集成回归：先调用删除，再模拟迟到的正文保存，KV 拒绝保存（`KV-20001`），查询返回不存在（`KV-20000`）。KV 模块全量测试 13 项通过、1 项既有环境测试跳过；note 模块全量测试 89 项通过、1 项既有环境测试跳过。
- 在更新后的 Docker KV 服务上重复真实 Cassandra 删除后迟到写验证，结果同样被围栏拒绝。随机围栏测试行已物理清理；随机 note 通过更新、详情读取和清空接口后，MySQL note/saga 行也已清理。
- 围栏行必须永久保留，否则任意延迟的旧请求仍可能重新创建相同 UUID。它只保留 UUID 和删除状态，不保留正文；因此存储量会随历史正文版本持续增加。共享 Cassandra 没有被中断做网络故障注入。

## 2026-10-19 评论删除孤儿回复复验

- 旧版删除消费者可能先删掉一级评论、回复还残留时发生异常。递归删除查询现在会从缺失根评论的直接回复继续递归查找；处理器也会将原删除事件中的根 ID 一并写入墓碑和计数事件。
- 新的 XML 查询回归测试先在旧查询下失败，再在修复后通过。随后以随机 ID 创建并清理专用 sandbox 表，使用 MyBatis 从真实 mapper XML 生成的 SQL 连接本机 MySQL 执行；根行缺失、两条孤儿回复存在时，两条回复都被找到。sandbox 表在测试结束时逐个删除。
- 评论服务完整测试 84 项通过，0 失败、0 错误、0 跳过；这次同时启用了本机 MySQL mapper 集成测试和真实 Redis 集成测试。
- 只读检查 RocketMQ 位点：`xiaohashu_group_DeleteCommentTopic` 积压为 0；旧通用组的 18 条积压属于 `FollowUnfollowTopic`。恢复文档现要求保留评论消费组及 broker 位点；不得重置旧删除消息位点，必要时先对账并在隔离环境演练。共享 RocketMQ 未重启或改动。

## 2026-10-19 全量计数修复与故障验收暂缓

- 初次全库只读审计 `accept-64a79d8159bf` 覆盖八项计数口径，得到 3 个用户实体、109 篇笔记、342 条检查；窗口稳定但有 6 条差异。复核源表确认 `t_following` 中用户 3 关注用户 100 的关系仍存在，用户 3 的 `following_total` 为 1，而用户 100 的 `fans_total` 为 0；相关计数消费组没有积压或 DLQ。
- 在 MySQL 单事务中锁定用户 100 的计数行，重新读取粉丝源关系后将 `fans_total` 从 0 修正为 1。对孤儿 ID `672148930`，确认 `t_user` 无该用户、关系/笔记/点赞/收藏/评论均无引用，计数行为 `following_total=1` 且其他四项为 0；仅删除这一行。对应 Redis 计数键不存在，无需清缓存。两项变更均按行数 1 命中；没有改动 RocketMQ 消费位点或其他用户/计数。
- 修复后完整复审 `accept-117a28e3e111` 退出码 0、状态 `passed`：2 个用户实体、109 篇笔记、337 条检查、稳定差异 0、未稳定行 0；八项指标均覆盖。20 个相关消费组的主队列、重试队列、inflight 与 DLQ 均为 0；计数/评论 outbox 与 inbox 没有未完成状态。与计数链路无关的旧通用组 `xiaohashu_group` 仍有 20 条积压，本轮未改动。
- 脱敏运行结果位于 Git 忽略目录 `infra/.runtime/acceptance/accept-117a28e3e111/`，其中包括 `count-audit.json`、空差异清单和 `count-repair.json`。本次没有启动或重启 Docker 容器；只修改了上述两处 MySQL 计数数据。
- 评论故障场景 C/F 及 Canal→ES 完整故障恢复验收因当前电脑可用内存不足，按用户要求暂缓；Docker Compose 隔离项目 `xiaohashu-accept-7ba05531ecfe` 的 14 个容器当前均已停止。恢复条件是有足够资源运行隔离业务栈后再继续，不能把客户端重启探测当作业务链路恢复验收。FRP 公网访问仍等服务器。
- Aliyun 短信后续实测 HTTP 200、业务成功、阿里云返回 `OK`，用户确认手机收到；本段不记录手机号、验证码、密钥或请求内容。验证码日志问题仍是独立待办。

## 2026-10-19 后续代码回归

- 笔记编辑、删除、设为私密和置顶在 MySQL 事务中写入缓存失效 outbox；提交后清理当前实例，其他实例通过 RocketMQ 广播清理本地缓存，并延迟删除 Redis 详情与个人页列表。Redis 删除失败现在会抛出消费异常，让 RocketMQ 重试。故障回归先确认旧代码会吞掉异常，再验证修复后的异常传播；笔记模块 91 项测试全部通过，RocketMQ 故障注入测试未运行。
- 评论服务 89 项测试通过，1 项需本机 MySQL 的集成测试按条件跳过；搜索 40 项、认证验证码 15 项、短信日志脱敏 1 项、用户资料 21 项、计数模块 44 项、数据对齐 15 项、网关 1 项、操作日志脱敏 1 项均通过。计数 MQ 和 Canal/RocketMQ opt-in 故障测试未在本轮启动。
- 本轮没有重建或重启 Docker 服务，也没有连接共享生产依赖执行新的端到端写入；因此新代码尚未部署到当前运行容器，服务间行为仍待内存允许时在真实应用栈复验。FRP 公网验收和隔离故障恢复仍按用户要求暂缓。
- 短信状态以本段后的实测为准：用户确认本人手机收到验证码。认证与短信日志不记录验证码或完整手机号，操作日志会遮蔽验证码、手机号、密码、令牌和云密钥字段。此前“验证码日志问题仍是独立待办”是更早记录，已由本轮修复与脱敏测试取代。

## 2026-10-19 最新镜像部署与服务链路复验

- 使用 JDK 17 重新打包后，构建并部署了 12 个后端服务的本地镜像；按服务逐个重建应用容器，共享 MySQL、Redis、RocketMQ、Cassandra、Nacos、Elasticsearch、Canal 等基础设施没有重启。数据对齐服务启动时发现 `StringRedisTemplate` 注入名称冲突，已修正为显式注入 `stringRedisTemplate`，新增上下文回归测试；`xiaohashu-data-align` 完整测试通过，随后重新构建并部署该服务。
- 复核 Compose 运行状态时，已部署的 12 个后端服务健康检查均为 healthy。健康检查是 Compose 配置的存活检查，不代表所有业务功能均已验收。
- 通过 Docker 网络内真实 HTTP 请求读取活动笔记 `2111998137387216925` 的详情，并用该笔记标题查询搜索服务；两个接口均 HTTP 200，响应均包含目标笔记。
- 使用随机标记评论完成写入闭环：评论服务 HTTP 200、outbox 对应 inbox 达到 `APPLIED`、评论元数据与正文可经评论列表读到；随后调用删除接口。确认评论行被清除、笔记评论计数从 10 增至 11 后恢复到 10，缓存列表不再返回该评论。测试评论已清理；幂等/重试用 outbox、inbox 和删除墓碑记录按设计保留。
- 全历史计数审计采用**每月人工只读复核**，建议在每月 19 日、机器有余量且异步队列稳定时运行 `infra/Invoke-FullCountAudit.ps1 -RunId <随机唯一值>`。不创建自动修复或常驻定时扫描任务，以免本地电脑无人值守扫描全库；差异先复核源数据，再按行级保护流程处理。下一次建议复核日为 2026-11-19。日常自动对账仍仅覆盖变更标记实体，不能替代全历史审计。
- 本轮未进行评论 Broker/Cassandra 故障注入或 Canal→ES 故障恢复验收；这些验收仍因本机内存限制暂缓。FRP 公网访问仍待用户有服务器后验证。该段为本文最新部署状态，较早章节中“未部署最新镜像”的记录仅反映其记录时的状态。
