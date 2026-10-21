# 教程交付状态与本地部署边界

更新日期：2026-10-19。之前一轮计数、笔记、关系、对齐、搜索、KV、评论、认证模块全量测试共 278 项通过，13 项按条件跳过；之后的改动和复测记录见下文。搜索刷新、评论投递与缓存、热门评论 499/500/501 边界、128 路 Redis 重建竞争、发布后立即读取正文均已有对应验证。100 篇笔记、991 条评论的本地演示数据已通过业务接口创建。用户 100 的笔记发布计数已从 49 校准到源表 52；随后对用户 3 的实际关注差异执行标记式修复并走通关注/取关计数链路。RocketMQ Broker、Cassandra 和 Canal 均在独立容器中做过实际停止/重启恢复验证，共享容器未被中断。公网 FRP 仍待用户有服务器后进行。真实阿里云短信发送已由用户确认收到。

教程主线已推进至 **28.10「Sentinel 熔断策略：异常比例与异常数」**，对应提交 `d4fe3fc`。本轮又按四份审阅后的可靠性设计补齐多项代码和部署文档；下面区分已实现、仍有边界和待运行验收的内容。

## 本轮实现与运行验收

### 2026-10-19 后续回归修复

笔记编辑、删除、设为私密和置顶现在在同一 MySQL 事务中写入缓存失效 outbox；事务提交后清理本实例缓存，outbox 再广播其他实例本地缓存删除并延迟清理 Redis。延迟删除消费者遇到 Redis 错误会抛出异常交给 RocketMQ 重试，不会吞错后确认消息。笔记服务关键回归 51 项、全量单测 91 项均通过。用户与笔记资料、计数、评论、搜索、网关、认证和日志脱敏的定向回归也均通过；本轮没有重建或重启 Docker 应用，所以这些新改动尚未做服务级联调。

### 19.13：其余五部分计数对齐作业

已补充粉丝数、笔记收藏数、作者获赞数、作者获收藏数、作者发布笔记数五项分片对账任务；与既有关注数、笔记点赞任务合计覆盖七项计数。每个标记实体先锁定对应的 `t_user_count` 或 `t_note_count` 行，再重新读源关系表并写入绝对值；锁失败、源数据无效或写入失败都会让 XXL-JOB 失败并可重试。过期标记表在最终对账仍有未解决差异或失败时保留。本机配置每日 03:00 运行五项补充任务，标记表清理每日 23:00 运行；对应执行记录需在本轮重新部署后复验。

证据：[数据对齐 Job 目录](../../xiaohashu-data-align/src/main/java/com/quanxiaoha/xiaohashu/data/align/job/)、[22.5 章节记录](../tutorial/22.5-es-periodic-count-index-rebuild.md)。

### 22.5：计数变更触发搜索索引刷新

Canal 订阅加入 `t_note_count`、`t_user_count`；笔记和用户重建统一经过有序、可重试的文档刷新入口，主体删除或不再可索引时会删除 ES 文档，并检查 Feign 响应和 ES 单文档写入结果。曾观察到 Canal 客户端连接断开后持续 `Broken pipe` 且不重连；现已增加连接失效、ACK 失败后的重连和 5 秒退避，并通过单测覆盖连接轮换与退避。运行中通过笔记发布及随后写入 10 条评论验证 ES：该笔记被搜索到，`commentTotal` 从初始值更新为 10。没有实际断开 Canal 来验证运行时重连。

用户资料定向验收：本地用户 100 临时改名后，ES 文档和搜索接口均返回新昵称；恢复原昵称后，ES 也已同步恢复。Canal 运行时断线重连仍未在共享容器中做故障注入。

证据：[22.5 章节记录](../tutorial/22.5-es-periodic-count-index-rebuild.md)、[笔记点赞数对齐 Job](../../xiaohashu-data-align/src/main/java/com/quanxiaoha/xiaohashu/data/align/job/NoteLikeCountShardingXxlJob.java)。

### 23.4：评论消息持久投递和消费状态

评论发送已使用事务 outbox，接收端先持久写 inbox 再确认 RocketMQ，inbox/outbox 具备租约、幂等键和持续退避重试；旧版删除事件会先持久化完整墓碑，阻止延迟发布复活评论，并保留正文 UUID/时间供异步清理。终态消息正文保留 30 天后分批清空，事件去重键长期保留。热门评论 Redis 重建使用跨实例锁和 owner token，防止较慢的旧快照覆盖新快照。另补了 [评论消息故障排查与恢复](../../infra/COMMENT-DELIVERY-RECOVERY.md)。评论模块完整测试 84 项通过；单测模拟 broker 发送失败，确认 outbox 会安排重试且不误标为 SENT。本机 MySQL 已存在 outbox、inbox、墓碑和计数幂等表，评论服务健康运行；Redis 锁脚本的 owner 校验和安全释放已在真实 Redis 验证。没有中断共享 broker 做运行时故障注入。

证据：[SendMqRetryHelper](../../xiaohashu-comment/xiaohashu-comment-biz/src/main/java/com/quanxiaoha/xiaohashu/comment/biz/retry/SendMqRetryHelper.java)、[23.4 章节记录](../tutorial/23.4-comment-mq-retry.md)。

### 笔记发布计数：持久投递、去重与单用户纠偏

新增 MySQL outbox：笔记新增/软删除与发布计数事件在同一事务中落库；note 写入、count 消费及纠偏共用按用户的 InnoDB 锁。计数消费者使用稳定事件 ID 账本避免 RocketMQ 重投时重复增减，并校验事件类型与增减方向。`database/25.13-note-publish-count-reliability.sql` 已在本机库应用，更新后的 count 和 note 容器均健康。

纠偏前确认 `NoteOperateTopic` 四个队列以及对应 retry topic 的 lag/inflight 均为 0，未发现该消费组的 DLQ。随后以单用户事务将用户 100 的 `note_total` 从 49 校准到 `t_note` 活跃笔记源数 52，并把已有 outbox 事件登记为已处理，避免迟到事件再次增量。其他四项计数与各标记表审计仍保持只读；计数对齐仍只覆盖标记实体，无法证明其他未标记指标全量无漂移。

JDK 17 目标测试共 44 项通过，其中包含 RocketMQ/Cassandra/Canal 的真实客户端隔离端口失败后连到健康端点，以及双 `CommentServiceImpl` 实例对同一随机笔记进行真实 Redis 快照重建。测试没有停止共享 Broker、Cassandra 或 Canal。

证据：[笔记计数迁移](../../database/25.13-note-publish-count-reliability.sql)、[单用户纠偏脚本](../../database/25.14-reconcile-note-publish-count.sql)、[运行复验](local-verification.md#2026-10-19-计数与依赖恢复复验)。

### 24.8：评论详情缓存缺失项处理

已移除 Caffeine 空占位，修正 Redis 局部 MGET 的 ID 对齐；正文缺失的评论不会进入详情缓存。评论列表 Lua 返回的有效空页现在按缓存命中处理，不会反复回源 MySQL 并触发缓存重建。评论模块 84 项测试通过；本轮未注入 Cassandra 延迟或 Redis 缓存竞争。

证据与既有实测记录：[24.8 章节记录](../tutorial/24.8-comment-caffeine-cache.md)、[CommentServiceImpl](../../xiaohashu-comment/xiaohashu-comment-biz/src/main/java/com/quanxiaoha/xiaohashu/comment/biz/service/impl/CommentServiceImpl.java)。

### 24.9：热门评论缓存边界和排序

已修复 Lua 的数值分数比较、满 500 条更新已有项时的裁剪顺序、热度下降/归零后的缓存失效，以及按 `heat DESC, commentId DESC` 的稳定排序和快照替换。JSON value serializer 导致的 `INVALIDATE` 参数加引号问题已修复；跨实例重建新增有 owner token 的 Redis 锁，真实 Redis 已验证 stale token 无法覆盖、非 owner 无法释放。499、500、501 候选边界实测保持最多 500 项；在恰有 500 项时更新已有成员不丢项，新候选只在排序靠前时替换最低分项，数值分数和同分 ID 顺序正确。评论模块 84 项测试通过；两条此前 PENDING 的缓存失效事件已重试为 APPLIED，generation 增至 2。并发重建竞争仍未做压力验证。

证据与既有实测记录：[24.9 章节记录](../tutorial/24.9-hot-comment-zset-lua.md)、[update_hot_comments.lua](../../xiaohashu-comment/xiaohashu-comment-biz/src/main/resources/lua/update_hot_comments.lua)。

### 26.x：点赞/收藏笔记 ID 范围

线上点赞/收藏状态路径已改用 Redis Set，以完整十进制字符串保存 Long 笔记 ID，不再将 Snowflake ID 作为 Roaring 位偏移。旧 Roaring key 常量和隔离测试仍在仓库中，但不属于该状态查询的活动生产路径。笔记完整模块测试通过；冷缓存回源与异步关系写入仍有独立的一致性边界，尚未做并发故障注入。

证据：[Roaring 脚本目录](../../xiaohashu-note/xiaohashu-note-biz/src/main/resources/lua/)、[现有脚本测试的 ID 取值](../../xiaohashu-note/xiaohashu-note-biz/src/test/java/com/quanxiaoha/xiaohashu/note/biz/RBitmapScriptsTest.java)、[Leaf 配置](../../xiaohashu-distributed-id-generator/xiaohashu-distributed-id-generator-biz/src/main/resources/leaf.properties)。

### 27.8：FRP 仅具备本地转发验证范围

仓库已提供 FRPS / FRPC 配置及可选 Compose profile。本机转发链路已经验证。用户目前没有服务器，因此公网 IP、防火墙、跨公网隧道和外网访问验收延期，等用户有服务器后再继续。

证据：[FRP 部署说明](../../infra/README.md#frp-from-tutorial-278)、[Compose 配置](../../infra/compose.yaml)。

### 27.x：发布后详情正文可见性

发布链路已在写入 MySQL 元数据前同步保存正文；数据库结果未知时不删除可能已被引用的正文。笔记详情使用 `contentRequired` 区分合法无正文与正文暂未就绪，暂缺正文不会写入长 TTL 缓存。通过业务接口发布演示笔记后，在生成下一篇前立即请求详情，返回了对应的 Cassandra 正文；不存在把暂缺正文缓存成长期空值的问题。另通过列表、评论及搜索接口验证首篇和末篇演示笔记。

代码证据：[KeyValueRpcService](../../xiaohashu-note/xiaohashu-note-biz/src/main/java/com/quanxiaoha/xiaohashu/note/biz/rpc/KeyValueRpcService.java)、[NoteServiceImpl](../../xiaohashu-note/xiaohashu-note-biz/src/main/java/com/quanxiaoha/xiaohashu/note/biz/service/impl/NoteServiceImpl.java)。

笔记正文写入现在有 MySQL 持久化 saga 恢复记录（`database/25.15-note-content-saga.sql`）。发布会先持久保存待恢复正文，Cassandra 写成功后再将笔记、计数 outbox 与 saga 完成状态放进同一 MySQL 事务；未确定的结果由租约恢复任务重试。正文编辑写入新 UUID，再以旧 UUID 做条件更新，只有指针仍未被并发修改时才切换；正文指针提交前会锁定有效租约，恢复任务不能同时接管该记录。若 Cassandra 写请求晚于租约返回，系统会检查 MySQL 指针并以 UUID 为键重新打开独立补偿记录。Cassandra `note_content.is_deleted` 围栏（`database/25.16-note-content-delete-fence.cql`）让同一 UUID 的写入和删除通过 LWT 串行化；即使删除先完成，之后抵达的旧写也会被拒绝。旧正文清理在元数据事务提交后进入持久队列，清空恢复会查询含软删除记录的笔记，仍被引用的正文不会被误删。恢复完成后正文载荷会清空，正文行转为永久删除标记，不能安全地设置 TTL。随机笔记已通过本机 Docker 服务执行编辑、详情读取和清空接口验证，测试数据已清理。

这使 MySQL 与 Cassandra 在服务短暂故障、进程重启或结果不明时可持续重试和恢复，但不构成跨库原子事务。每次正文版本被清理都会留下一个很小的 Cassandra 围栏行，随历史版本数增长；这是阻止无上限迟到写复活正文所需的保留成本。Cassandra 的永久数据丢失仍要依赖 Cassandra 备份恢复。共享基础设施没有被中断做故障注入；新版需要先应用 MySQL 25.15 与 Cassandra 25.16 迁移，再部署 KV 和 note 服务。

## 23.8 评论批量持久化的当前状态

原章节实现已在本轮升级为可恢复的 outbox/inbox 流程：固定正文 UUID、幂等写入评论元数据并在事务中持久化计数/缓存后续事件；KV 写入失败不确认 inbox。相关模块测试通过，数据库迁移已存在、服务健康运行；本轮没有在真实服务链路中模拟 KV 或 Broker 中断。

MySQL 和 Cassandra 仍不是一个分布式事务；系统通过稳定 UUID、重试和持久状态恢复，不承诺跨库原子提交。

## 仍未验收的边界

- 计数对账任务仍按日增量标记修复；2026-10-19 另完成一次全历史快照审计，覆盖 2 个用户实体、109 篇笔记、337 条八指标检查，审计通过且稳定差异为 0。审计前发现的用户 100 粉丝数差异已按源关系修复；一个没有用户及任何源数据引用的孤儿计数行已清理。该次通过只代表当时的数据库快照，未来仍需持续审计。证据见 [本地复验记录](local-verification.md#2026-10-19-全量计数修复与故障验收暂缓)。
- RocketMQ Broker 发送端、Cassandra 驱动会话、Canal 客户端都在独立容器实际重启后恢复；共享业务容器与共享基础设施未被中断。评论 A/B/D/E 场景已有先前记录；Cassandra 故障场景 C 和真实 DLQ 回收场景 F 尚未实测。Canal 客户端曾做隔离容器重启探测，但没有验证同一 search 进程在 Canal/ES 中断后消费真实 binlog 并刷新 ES；ACK 失败窗口仅由测试覆盖，未在真实链路复现。由于当前电脑可用内存不足以启动隔离业务栈，评论与 Canal→ES 故障恢复验收暂缓；验收 Compose 项目 `xiaohashu-accept-7ba05531ecfe` 的容器保持停止，待有足够运行资源后继续。
- 双评论服务实例完整热评重建已在真实 Redis 通过受控并发验证；本轮将竞争者增至 128，只有一个实例执行数据库查询且最终快照正确。尚未做持续吞吐量基准或长时间 soak test。
- MySQL、Cassandra 不是分布式事务；笔记正文 saga、稳定 UUID、outbox/inbox、幂等和重试可在短暂故障后恢复，但仍可能暂时不一致，无法承诺跨库原子提交。共享依赖的真实业务故障中断验收暂缓，Cassandra 永久数据丢失需依靠备份。
- 27.8 FRP 只做过本机转发；公网服务器、防火墙和跨公网访问要等用户有服务器后再验收。
- Aliyun 短信此前已完成接口 HTTP 200、业务成功，阿里云业务码 `OK`，用户确认手机收到；历史记录中“auth 容器无凭据、未发送”只描述更早的状态。真实手机号、验证码、请求内容和密钥不写入文档。验证码不写入业务日志，短信日志和操作日志中的手机号及敏感字段已脱敏。

证据：[23.8 章节记录](../tutorial/23.8-comment-consumer-bo-cleaning.md)、[Comment2DBConsumer](../../xiaohashu-comment/xiaohashu-comment-biz/src/main/java/com/quanxiaoha/xiaohashu/comment/biz/consumer/Comment2DBConsumer.java)。

## 本次 Docker 与原版前端部署补充

- 原版前端现位于后端仓库的 `../xiaohashu-vue3`，即 `F:\code\xiaohashu\xiaohashu-vue3`，通过前端镜像加入本地 Compose 应用。
- 新增 `docker` profile 的只读前端适配接口，为原版前端提供频道、话题、发现及个人页笔记列表。这些接口属于本地部署联调补充，完成度应单独记录，不计为上述教程缺项的补齐。证据：[DockerFrontendController](../../xiaohashu-note/xiaohashu-note-biz/src/main/java/com/quanxiaoha/xiaohashu/note/biz/controller/DockerFrontendController.java)、[应用 Compose](../../infra/compose.app.yaml)。
- Count 用户计数接口原有约 50% 随机故障用于 Sentinel 教学。现在由 `tutorial.sentinel.failure-demo-enabled` 控制：Docker 配置设为 `false`，普通 dev 环境默认保留 `true`。证据：[UserCountController](../../xiaohashu-count/xiaohashu-count-biz/src/main/java/com/quanxiaoha/xiaohashu/count/biz/controller/UserCountController.java)、[Docker 配置](../../infra/docker/application-docker.yml)。
- Docker 下另用显式绑定当前请求上下文的网关过滤器，解决服务发现切换线程后身份头缺失的问题；普通 dev 保留教程过滤器。此前已实际观察到缺失身份导致笔记事务回滚而接口仍返回成功，部署修正与复测范围见 [local-verification.md](local-verification.md)。

Compose 启动、健康检查通过、浏览器可访问及个别接口成功，各自只证明对应部署或功能路径。教程主线已完成到 28.10；全历史计数审计在 2026-10-19 的快照中通过，但计数任务本身仍是标记驱动；评论/Cassandra 与 Canal→ES 的业务故障恢复验收因本机内存不足暂缓，FRP 公网访问等用户准备服务器后继续。不能据 Docker 运行成功宣布全部教程达到生产质量。

## 2026-10-19 最新镜像与接口复验

- 已重新构建并部署 12 个后端服务镜像，应用容器均健康；共享基础设施保持运行，未因部署而重启。
- Docker 网络内实测笔记详情与搜索接口，均成功返回同一活动笔记。
- 评论发布、持久化、列表读取、计数增加、删除、计数恢复和缓存失效完成一条真实闭环；随机测试评论已清理，系统幂等记录按设计保留。
- 全历史只读计数审计决定每月人工执行，建议每月 19 日运行一次；本地电脑不增加自动扫描/修复任务。日增量对账仍只覆盖变更标记实体。
- 评论/Cassandra 故障注入、Canal→ES 故障恢复、FRP 公网访问仍未验收，原因和恢复条件见 [本地复验记录](local-verification.md)。
