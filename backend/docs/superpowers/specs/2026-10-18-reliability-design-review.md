# 四份可靠性设计稿审阅记录

审阅日期：2026-10-18。原稿提交：`6ccf3cc`。范围：四份设计文档、对应业务代码、Compose/启动脚本和只读 Docker 元数据。

## 结论

**原稿不能直接按“设计已通过”实施。** 四个方向都有价值，但原稿把局部幂等误当作全链路幂等，把覆盖写误当作在线校准安全，并遗漏了局域网媒体与备份可恢复性条件。

已在原设计稿中修正明确错误、删除不成立的承诺并列出必要前置条件。在线计数回写的源事件版本/消费水位、全链路消息状态确认和热榜重建版本协议仍需在实施计划中落实；不能以“补了类/加了 Lua/加了 outbox 表”代替这些边界的验证。

本轮是设计审阅与文档修订，未修改业务代码、构建镜像、重启服务、迁移或删除业务数据。

## 必须修改的问题

### 1. 在线覆盖式对账可能造成新的计数错误（P1）

源关系已经提交、计数 MQ 尚未到达时，COUNT 会包含尚未应用的事件；覆盖写后该消息再 `+1` 就会双计。COUNT 与 UPDATE 之间的新增量也可能被覆盖。反复运行作业和仅给作业加锁均无法解决迟到消息。

证据：`xiaohashu-data-align/.../job/FollowingCountShardingXxlJob.java:75`、`NoteLikeCountShardingXxlJob.java:78`；`xiaohashu-count/.../mapper/UserCountDOMapper.xml:123`、`NoteCountDOMapper.xml:101`。

必要修订：不带源事件版本/消费水位协议时只执行比对审计；修复模式必须停写并确认相关关系和计数消息处理完毕。若目标仍是在线自动校准，需实现源事件版本、已消费水位和迟到事件规则，并对 Redis 回填同步保护。原稿不能再把覆盖写本身视为在线安全的证据。

### 2. 增量标记不完整，物理分片与执行器分片不匹配（P1）

增量消费者吞掉 SQL 失败后仍加入 Bloom；Bloom 阳性还会跳过数据库写入，因此可能永久漏标。当前物理分片为 3，而两个 Job 用执行器 shardIndex 直接拼表；只有一个 data-align 实例时只处理 `_0`。

证据：`TodayNoteLikeIncrementData2DBConsumer.java:89`、`:105`、`:113`；`FollowingCountShardingXxlJob.java:44`；`xiaohashu-data-align/.../resources/application-dev.yml:70`。

必要修订：SQL 唯一键负责持久去重，真实异常触发重试，Bloom 不得作为唯一跳过条件；源记录提交到事件发送之间也需可靠交付。枚举实际物理分片，用模运算分配到执行器，并继续重试旧日积压表。

### 3. 作者增量实体和计数口径写错（P1）

作者获赞、获收藏、发布数运行表均存 `user_id`；原稿却写作 `note_id` 再反查作者。19.1 示例 SQL 的发布表也与运行建表 Mapper 冲突。作者获赞/收藏当前在笔记删除时不会扣减；若只在对账 SQL 中排除软删除笔记，就会因是否触发校准而得到不同业务口径。

证据：`CreateTableMapper.xml:31`、`:58`，`InsertMapper.xml:21`，`database/19.1-data-align-daily-tables.sql:45`；`CountNotePublishConsumer.java:40`。

必要修订：作者三表统一 `user_id`；发布数排除删除笔记，作者获赞/收藏保持现有历史累计口径。改变后两个指标口径必须同时改在线事件和状态变更标记。既有错误表先核对结构，不能删表重建。

### 4. 索引重建失败会被当成成功确认，覆盖写还会乱序（P1）

重建 RPC 捕获 ES 异常后返回成功，资料更新 bulk 没检查单项失败；Canal 将 ack 这些事件。客户端订阅仍只有用户和笔记主体表，仅加事件分支收不到计数变化。Job 直接 RPC 与用户资料 bulk 的旧快照可以晚写覆盖新文档；按 ID 覆盖幂等不能保证顺序。

证据：搜索 `NoteServiceImpl.java:200`、`UserServiceImpl.java:115`，`CanalSchedule.java:72`、`:265`；`xiaohashu-search/.../resources/application-dev.yml:10`。

必要修订：同步修改订阅；检查 bulk 每一项和 Feign 业务响应，失败传播至 rollback；统一同实体刷新顺序/源版本。主体不存在或不符合索引条件时删除 ES 文档，替代当前空查询 no-op/DELETE 仅日志行为。未发现该链路写回 MySQL 形成循环。

### 5. 评论 `INSERT IGNORE` 不等于正文、计数和删除重放幂等（P1）

消费者每次重新生成 contentUuid；重复消费会保留 MySQL 旧 UUID，却把正文写入新 UUID。整批只检查 `insertedRows>0`：`[旧 A, 新 B]` 会给 A 和 B 都发计数。事务提交后才发送计数且失败只记日志；宕机重投时新增数为零又会跳过后续动作。评论物理删除后，旧发布消息还能重新插入。

证据：`Comment2DBConsumer.java:136`、`:163`、`:172`、`:190`；`CommentDOMapper.xml:41`。

必要修订：正文键在事件中固定；逐事件持久 inbox/完成状态独立于评论行，保留删除墓碑；评论事务内保存后续 outbox 事件。下游增量按事件 ID 去重并与计数事务提交，不能以主键约束或整批新增行数替代全链路幂等。

### 6. Broker 接收、进程内入缓冲和业务保存是三个不同的完成点（P1）

发送端 SENT 不能代表 APPLIED。现有一些消费者将数据放入进程内 BufferTrigger 就返回成功，进程退出可能丢掉 broker 已确认的数据。首次启动的新评论消费组从最后位点开始，也可能跳过已经入队的待处理消息；broker 消费失败和 DLQ 不会自动把发送端记录改成待发送。

证据：`CountNoteCommentConsumer.java:43`、`CountNoteChildCommentConsumer.java:52`、`CommentHeatUpdateConsumer.java:51`、`Comment2DBConsumer.java:82`。

必要修订：租约更新采用 owner/version 条件；明确 SENT 与 APPLIED，持久化待处理状态、结果巡检/DLQ 重驱和新消费组起点。若保留异步聚合，先持久化 inbox 再确认消费。HTTP 重试幂等不包含在仅以新生成 commentId 去重的承诺内。

### 7. 评论缓存还会缓存正文缺失；热门榜下降和同分排序不完整（P2）

去掉 Caffeine 空字符串及修正 MGET 索引是必要修复，但正文 KV 失败时当前组装仍缓存正文为 null 的详情。热度消费者过滤 `<=0`，降为零到不了 Lua；只保留 500 条的脚本不知道第 501 条候选，现有 SQL pipeline 回填又不清旧成员、不裁剪。SQL 只有 heat 排序，同分分页不稳定；最后按旧详情 heat 重排会推翻 ZSet 顺序。

证据：`CommentServiceImpl.java:440`、`:466`、`:488`、`:865`、`:918`、`:954`；`CommentHeatUpdateConsumer.java:93`；`CommentDOMapper.xml:294`、`:309`。

必要修订：完整正文才缓存；热度下降（含 0）触发热榜失效与 SQL 回退，临时 key 加版本条件原子替换。统一 `heat DESC, commentId DESC`，成员固定宽度编码，按 ZSet ID 序列组装。Lua 先校验整批参数再写入。

### 8. 保留旧正文消费者会覆盖编辑结果，盲目补偿会删除有效正文（P1）

同步发布后旧事务消息仍携带原正文；当前消费者会无条件 upsert。编辑复用 UUID，旧消息迟到会覆盖新正文，清空正文后也可重新写入。SQL 异常不证明未提交，立即删除 KV 可能删除已提交笔记的正文；同步 KV 又放大了回查时暂时无元数据的窗口。

证据：`SaveNoteContentConsumer.java:35`、`NoteContentServiceImpl.java:27`、笔记 `NoteServiceImpl.java:607`、`PublishNote2DBLocalTransactionListener.java:52`、`:107`。

必要修订：新链路不再通过发布消费者写正文，切换前处理完旧消息。确定 SQL 未提交才补偿；未知状态查证并返回 UNKNOWN，禁止直接删正文。回查要区分仍执行、确定失败和查询失败。详情缓存包装需有 contentRequired，现有公开 VO 不能区分合法无正文和缺失正文。

### 9. Set 解决 ID 范围，但不能凭“原子合并”解决异步关系竞态（P2）

Set 应统一十进制字符串序列化，明确空集合 ready 状态和批查原顺序。数据库有 MQ 延迟，简单 SADD 旧快照会复活刚取消的成员；按存在跳过初始化又可能把局部集合当完整集合。原稿还同时声称滚动切换与避免双版写，存在矛盾。

证据：笔记 `RedisTemplateConfig.java:27`、`NoteServiceImpl.java:905`、`:1327`，`LikeUnlikeNoteConsumer.java:107`。

必要修订：本轮明确只保证完整 ID 不溢出/不串号，冷重建和消息失败是独立一致性边界。缓存切换采用停写、消息处理完毕、单版本部署，不宣称双版本滚动写。补 `2^32` 边界、相同低位不同高位、`2^53` 以上相邻值验收。保留 Roaring 并在 Java 拆高低位也是备选，但存在稀疏 key 成本，不能在无实测时声称省内存。

### 10. 仅开放 UI 端口不能完成局域网体验；备份与新机恢复条件缺失（P1/P2）

MinIO 上传返回 `host.docker.internal:9000`，前端直接作为图片/头像 URL；其他局域网设备不可用。当前 Prepare-Local 依赖旧容器和 F 盘配置，外部卷名固定为本机哈希，换根路径无法全新安装。原备份描述还遗漏配置、Cassandra 快照导出、MinIO 元数据、Canal 位点恢复条件和失败标记。

证据：`MinioFileStrategy.java:53`、`infra/docker/application-docker.yml:82`、`infra/frontend/nginx.conf:18`、`infra/Prepare-Local.ps1:36`、`:41`、`:82`，`infra/compose.yaml:270`。

必要修订：内网 storage endpoint 与同源只读 `/media` 地址分离，兼容历史本地 URL；参数化路径和外部卷，提供显式新机初始化/恢复入口并校验路径。备份明确多数据库、实际导出的 Cassandra SSTable/schema、MinIO 对象和必要配置、`.runtime`/位点恢复边界，隔离恢复验证不得引用现用卷。TCP 健康只表示监听，业务链路另测。

## 已核实的运行边界

- 只读 `docker ps -a` 显示当前小哈书 Compose 常驻服务均在运行；已有健康检查的服务报告 healthy。这不是新增业务修复验收。
- 前端当前仍为 `127.0.0.1:8088`，局域网开放属于待实施内容。
- 本机 Redis 镜像 RepoDigest 与设计稿引用一致。
- 旧小哈书独立容器已经移除，剩余 dtk、pgvector、searxng、redisinsight 等其他程序容器不属于本轮清理范围。
- FRP 公网验证继续延期；没有公网服务器时不据本机转发结果宣称公网验收通过。

## 修订后的实施界限

缓存索引错位、数值比较、完整正文缓存检查、地址分离和参数化配置等确定修复可先分批落地。计数自动回写、逐事件消息状态和缓存重建必须按上列协议补齐后实施和验收；这些是技术设计条件，不需要用户反复确认已经授权的修复范围。

仅本次文档修订的格式检查可作为审阅产物验证；后续代码完成仍须 JDK 17 编译、运行 Docker 服务、随机数据接口与故障边界验证后才能标记修复完成。

## 后续实施状态（2026-10-19）

审阅后已按四份修订设计推进实现：评论持久投递/消费、评论与笔记缓存边界、全 Long 笔记 ID 集合、五项计数审计、Canal/ES 有序重建，以及本机 Compose/备份恢复改造均已写入当前工作区。相关模块测试、全项目 12 个后端镜像构建、前端镜像 `nginx -t` 和 Compose 健康检查已完成；评论可靠性表已在当前 MySQL 中存在。五个审计任务以及经过最终审计的过期标记表清理任务已在 XXL-JOB 实际执行成功，前端媒体代理已实测 GET/HEAD/Range。

后续运行检查又发现，评论热榜 Lua 的 `INVALIDATE` 字符串经默认 JSON value serializer 传入时会带双引号，导致 Lua 报 `unknown hot-comment action`，两条缓存失效 inbox 事件因此持续重试。现已为所有 `update_hot_comments.lua` 调用统一指定原始字符串参数和 Long 返回值序列化；新评论镜像健康运行后，MySQL 中两条历史事件已重试为 APPLIED，Redis generation 更新且对应缓存失效。回归测试先在原实现上失败，再在修复后通过；评论模块完整测试为 64 项通过。

最终实现审阅又发现三个问题，现均已修复：热门评论重建在 SQL 查询前获取按笔记隔离、带 TTL 的 Redis owner 锁，替换和释放均核对 token 与 generation；旧版删除事件从事件/现存删除树构建完整墓碑并先持久化，缺元数据时保持可重试；终态 Inbox/Outbox payload 保留 30 天后分批清空，同时长期保留幂等键和墓碑。真实 Redis 已验证旧 owner 被拒绝和锁释放规则，MySQL 清理语句已通过只读 `EXPLAIN`。评论模块完整测试现为 73 项通过，重建后的评论容器健康。

仍未做的专项验收包括 Broker/Canal/Redis/Cassandra 故障注入、Canal→ES 计数变更定向验证、热门评论 Redis 499/500/501 并发边界、发布后立即查正文，以及公网 FRP。一个既有宿主机 RocketMQ 压测 `MQTests` 被单独排除：它向真实 topic 发送 1620 条消息，且宿主机无法解析 Broker 在 Docker 网络内通告的名称；其他相关模块套件均通过。以 [本地复验记录](../../deployment/local-verification.md#2026-10-19-可靠性改造复验) 为当前运行证据，不能把历史整组应用验收或本轮单项验证扩展成上述未跑场景的通过结论。
