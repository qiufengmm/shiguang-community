# BufferTrigger 计数改造 — 验证报告

日期：2026-10-20
范围：`xiaohashu-count` 粉丝数 / 笔记点赞 / 笔记收藏 三个聚合链路，以及 data-align 兜底链路。

---

## 一、环境与口径

| 项 | 值 |
|---|---|
| 镜像 | `xiaohashu/count:local`，由本次改动后重新打包构建（JDK 17） |
| 部署 | Docker Compose 本地栈，27 个容器全部 healthy |
| 注入方式 | `mqadmin sendMessage` 直接投递到计数 Topic（绕过 API 与关系服务） |
| 数据 | 合成 ID 段 `9900000000000000xx`，验证后全部清理 |

> **口径限制**：本次没有走真实关注接口，`t_following` 里没有对应关系行。因此**能验证聚合链路，不能验证夜间对账的修复效果**（对账以源表为准，源表为空会算出 0）。

---

## 二、冒烟结果（全部通过）

| 场景 | 期望 | 实测 | 结论 |
|---|---|---|---|
| 10 条关注 | 粉丝 +10 | Redis 10 / MySQL 10 | ✅ |
| 30 条关注（续） | 累计 40 | Redis 40 / MySQL 40 | ✅ |
| 5 条取关 | 40 − 5 = 35 | Redis 35 / MySQL 35 | ✅ |
| 3 条笔记点赞 | 笔记 +3、作者 +3 | Redis 3/3，MySQL 3/3 | ✅ 双维度都写对 |

日志格式符合预期：

```
CountFansConsumer    : ==> 【计数服务：粉丝数】聚合完成，原始消息 10 条，聚合后用户 1 个，已投递落库 MQ
CountFans2DBConsumer : ## 【计数服务：粉丝数落库】已完成 1 个用户的增量累加
```

**聚合阈值未能触发**：`mqadmin` 每条消息 JVM 启动约 3.5 s，到达间隔远大于 `linger` 的 1 s，所以每批只有 1 条。凑够 1000 条一批需要真实压测流量，本次未覆盖。

---

## 三、强杀实验（核心结果）

### 方法

1. `docker pause count` —— 消费进程冻结，消息在 broker 堆积
2. 投递 20 条关注消息到 `CountFansTopic`
3. `docker unpause` 后 0.3 s 用 `docker kill --signal=KILL` 强杀
4. `docker start` 重启，等待 healthy 后读数

### 结果

| 项 | 值 |
|---|---|
| 发送消息数 | **20** |
| 强杀瞬间 | Redis 7 / MySQL 7 |
| 重启后稳定 | **Redis 17 / MySQL 24** |

**三个数字互不一致。** Redis 比应到值少 3，MySQL 比应到值多 4。

### 归因（推断，非逐条追踪）

两个非幂等环节各自独立出错，误差不会抵消：

| 环节 | 失败模式 | 表现 |
|---|---|---|
| 一级消费者（BufferTrigger） | 消息已 ACK 但还在内存缓冲 → 强杀即丢 | Redis 比预期少 |
| 一级消费者的 MQ 位点 | 重启后已处理消息被重投 → 再累加一次 | Redis 比预期多 |
| 二级消费者（落库） | 它自己的消费位点也未提交 → 2DB 消息重投 → `+delta` 再加一次 | MySQL 比 Redis 再多 7 |

MySQL 24 − Redis 17 = 7，与强杀瞬间已应用的 7 条完全吻合，支持上述归因。

### 结论

**这条链路在强杀下既会丢也会重，而且两个方向的误差不会互相抵消，最终得到一个既偏大又不可解释的数字。** 这比单纯"丢数据"更难排查，因为对账前没有任何依据能判断该往哪个方向修。

---

## 四、兜底链路的真实状态（发现一个更严重的问题）

计划中的兜底是：`data-align` 独立消费组写日增量标记 → 凌晨 3 点分片对账重算并覆盖。

实际检查发现：

```
Table 'xiaohashu.t_data_align_fans_count_temp_20261020_2' doesn't exist
```

**当天的日增量表根本不存在**（只有 1017/1018/1019 的）。原因是 `createTableJobHandler` 在**每天 23:00 预创建次日**的表，而 10-19 23:00 那一刻栈没在跑，今天的表就没人建。

后果：**今天 00:00 到 23:00 之间所有的计数变更，标记一条都没写进去，凌晨对账扫不到这些实体，兜底链路今天是完全失效的。** 这期间丢的数据是不可恢复的。

### 已处理

按 1019 的表结构补齐了今天 21 张表（7 个维度 × 3 分片），并验证标记恢复写入：

| 标记表 | 实测 |
|---|---|
| `t_data_align_following_count_temp_20261020_1` | 1 行 ✅ |
| `t_data_align_fans_count_temp_20261020_2` | 1 行 ✅ |
| `t_data_align_user_like_count_temp_20261020_2` | 1 行 ✅ |

### 遗留风险

1. 建表任务漏跑一次，当天就失去兜底能力，且**没有任何告警**。
2. 日期分片表名由 `userId % tableShards` 决定，表不存在时插入直接报错，消费者只打异常栈，不重试放大也不进 DLQ。

---

## 五、对简历与面试的影响

### 能说的

- 聚合链路已跑通，粉丝数正负增量、笔记/作者双维度写入都正确。
- 自己实测过强杀，知道会丢也会重。

### 不能说的

- ❌「保证不丢」——实测丢过。
- ❌「幂等」——`+delta` 非幂等，实测重复累加过。
- ❌「对账能修回来」——**只有在当天日增量表存在的前提下才成立**，而且这个前提没有自动保障。

### 面试可以主动交底的一句

> 我实测过强杀：发 20 条，最后 Redis 17、MySQL 24。因为我这条链路上有两个非幂等环节，一个在缓冲里丢，一个在重投时多加，误差不抵消。兜底靠凌晨对账，但前提是当天的日增量表已经被预创建——我这次就发现建表任务漏跑了一天，那天的兜底等于没有。所以这套东西的正确说法是"最终一致，且有前置依赖"，不是"可靠"。

---

## 六、后续待办

1. ~~`createTableJobHandler` 加"当天表是否存在"的启动自检 + 告警~~ → **已完成，见第七节**
2. ~~二级消费者落库前做幂等（`msgId` 去重），把 MySQL 多加的问题修掉~~ → **已完成，见第八节**
3. 写链路压测：需要走真实关注接口 + 有效 Token，拿到真实 TPS 后才能往简历写数字。
4. 用真实关系数据跑一次对账，验证 `repair()` 确实能把偏差修回。
5. 一级消费者（`CountFansConsumer` 等）的 Redis 增量仍未幂等，见第八节末尾。

---

## 七、日增量表启动自检（已落地）

### 为什么补

`createTableJobHandler` 是 23:00 预建**次日**的表。它漏跑一次，第二天 0 点起这张表就不存在，
`TodayUserFollowIncrementData2DBConsumer` 插标记表直接报 `Table ... doesn't exist`，
**兜底链路静默失效，且没有任何告警**。本次实测就踩到了这个坑（10-19 23:00 栈没跑，10-20 全天无兜底）。

启动自检和定时任务的分工：

| | `createTableJobHandler`（XXL-JOB） | `DailyMarkerTableGuard`（启动自检） |
|---|---|---|
| 触发 | 每天 23:00 | 应用启动 |
| 建哪天的表 | 明天 | 今天 + 明天 |
| 作用 | 正常情况下的主路径 | 漏跑后的兜底，保证"至少有一天的表存在" |

两者不重复：定时任务是预防，自检是补救。

### 改动清单

| 文件 | 说明 |
|---|---|
| `xiaohashu-data-align/.../job/DailyMarkerTableGuard.java` | 新建。`@EventListener(ApplicationReadyEvent.class)` |
| `xiaohashu-data-align/.../domain/mapper/TableExistenceMapper.java` | 新建。接口 |
| `xiaohashu-data-align/src/main/resources/mapper/TableExistenceMapper.xml` | 新建。查 `information_schema.tables` |
| `xiaohashu-data-align/src/test/.../job/DailyMarkerTableGuardTest.java` | 新建。4 个用例 |

### 关键实现

期望表清单由 `CountAuditMetric.values()` × `tableShards` 生成（7 维度 × 3 分片 = 21 张），
不硬编码表名后缀，日期变了清单自动跟着变：

```java
List<String> expectedTableNames(String dateSuffix) {
    List<String> names = new ArrayList<>();
    for (CountAuditMetric metric : CountAuditMetric.values()) {
        for (int shard = 0; shard < tableShards; shard++) {
            names.add(metric.getTablePrefix() + TableConstants.buildTableNameSuffix(dateSuffix, shard));
        }
    }
    return names;
}
```

只补缺失的，不全量重建（否则每次重启都跑 21 条 DDL）：

```java
Set<String> missingSet = expected.stream().filter(n -> !existing.contains(n)).collect(toSet());
for (CountAuditMetric metric : CountAuditMetric.values()) {
    for (int shard = 0; shard < tableShards; shard++) {
        String name = metric.getTablePrefix() + TableConstants.buildTableNameSuffix(suffix, shard);
        if (missingSet.contains(name)) {
            createTable(metric, suffix);   // 内部 switch 分发到 7 个 createTableMapper 方法
        }
    }
}
```

`switch` 里留了 `default -> throw new IllegalStateException("Unhandled count metric: " + metric)`。
**这是故意的**：将来新增计数维度却忘了在自检里补分支时，要立刻炸出来，而不是静默跳过、
等到某天兜底失效才发现。

### 实测验证

1. 故意删掉 2 张表（`t_data_align_fans_count_temp_20261020_2`、`t_data_align_note_like_count_temp_20261020_1`），剩 19 张
2. `docker compose up -d data-align` 重建容器
3. 日志：

```
WARN DailyMarkerTableGuard : ## 日增量标记表自检发现缺失，date=20261020，缺失 2 张，开始补建。
     缺失清单: [t_data_align_fans_count_temp_20261020_2, t_data_align_note_like_count_temp_20261020_1]
WARN DailyMarkerTableGuard : ## 日增量标记表补建完成，date=20261020，补建 2 张。
WARN DailyMarkerTableGuard : ## 日增量标记表自检发现缺失，date=20261021，缺失 21 张，开始补建。
WARN DailyMarkerTableGuard : ## 日增量标记表补建完成，date=20261021，补建 21 张。
```

4. 表数量核对：`20261020` → 21 张，`20261021` → 21 张，容器 `Up (healthy)`
5. `mvn -o -pl xiaohashu-data-align test` → 20 项通过，BUILD SUCCESS

顺带把 10-21 的 21 张也预建了，今晚 23:00 再漏一次也不会重演。

### 还没解决的

自检只在**启动时**跑。如果应用一直不重启、某天 23:00 的建表任务又漏了，
第二天仍然没有新表。要彻底闭合，需要再加一条"每天 0 点后检查当天表"的 XXL-JOB。
考虑到当前只是本地单机验证环境，暂不做，记在这里。

---

## 八、二级消费者幂等改造（已落地）

### 为什么改

第二节强杀实验的结果是 **Redis 17 / MySQL 24**（应到 20）。多出来的 7 条就是重投导致的重复累加：

- `insertOrUpdateFansTotalByUserId` 的 SQL 是 `ON DUPLICATE KEY UPDATE fans_total = fans_total + delta`
- 消息被处理完、但消费位点还没提交到 Broker 时进程被杀 → 重启后从旧位点重投 → **同一批 delta 被加两次**
- `+delta` 本质不可重放，这是设计缺陷，不是偶发 bug

### 方案：数据库幂等日志表 + 同事务

新建 `t_count_consume_log(msg_id, consumer_group, topic, created_time)`，主键 `(msg_id, consumer_group)`。
落库流程变成：

```java
transactionTemplate.execute(status -> {
    if (countConsumeLogMapper.insertIgnore(msgId, topic, group) == 0) {
        return false;                        // 主键冲突 = 重复投递，整批跳过
    }
    countMap.forEach((userId, delta) -> mapper.insertOrUpdateFansTotalByUserId(delta, userId));
    return true;                             // 登记与累加在同一个事务里提交
});
```

**为什么不用 Redis `SETNX` 占位**：占位和处理不在同一事务里，中间崩溃会留下"占位了但没落库"的空窗，
重投时被当成重复消息跳过，数据就永久丢了。要用就得加"失败删 key + 状态机 + 超时"，复杂度高得多。
数据库方案靠本地事务天然原子，不需要补偿逻辑。

**为什么不在应用层缓存 msgId**：进程重启后缓存就没了，挡不住重投。

### 改动清单

| 文件 | 说明 |
|---|---|
| `database/25.16-count-consume-idempotency.sql` | 新建表 |
| `count-biz/.../domain/mapper/CountConsumeLogMapper.java` + `mapper/CountConsumeLogMapper.xml` | `INSERT IGNORE` |
| `count-biz/.../consumer/CountFans2DBConsumer.java` | 改 `RocketMQListener<MessageExt>`，拿 msgId |
| `count-biz/.../consumer/CountNoteLike2DBConsumer.java` | 同上，笔记 + 作者双表一起进事务 |
| `count-biz/.../consumer/CountNoteCollect2DBConsumer.java` | 同上 |
| `count-biz/.../consumer/CountCommentLike2DBConsumer.java` | 同上 |
| `data-align/.../domain/mapper/CountConsumeLogCleanMapper.java` + XML | 按保留期清理 |
| `data-align/.../job/DeleteTableXxlJob.java` | 每天清理 7 天前的日志（保留期 > RocketMQ 72h） |
| `count-biz/src/test/.../CountConsumerTestSupport.java` | 测试脚手架，直通事务模板 |
| 4 个消费者测试类 | 补「重复投递不重复累加」用例 |

`CountFollowing2DBConsumer` **没改**：它走的是回源重算（`refreshFollowing`），重放结果相同，本身幂等。

### 验证一：位点回退重放（决定性证据）

比重杀更严格——直接把消费位点重置到最早，强制重放 topic 里全部消息。

粉丝数链路：

| 步骤 | Redis `fansTotal` | MySQL `fans_total` | `t_count_consume_log` |
|---|---|---|---|
| 并发发 5 条关注 | 5 | 5 | +2 条（聚合成 2 批：4 条 + 1 条）|
| 位点重置到 earliest，重放 | 5 | **5（不变）** | 53（不变）|

日志：
```
WARN CountFans2DBConsumer : ## 【计数服务：粉丝数落库】重复消息，整批跳过, msgId=7F0000010007238E0D816473E4770001
WARN CountFans2DBConsumer : ## 【计数服务：粉丝数落库】重复消息，整批跳过, msgId=7F0000010007238E0D816473E8680002
```

笔记点赞链路（双表写入）：

| 步骤 | 笔记 `like_total` | 作者 `like_total` | Redis `likeTotal` |
|---|---|---|---|
| 并发发 3 条点赞 | 3 | 3 | 3 |
| 位点重置到 earliest，重放 | **3（不变）** | **3（不变）** | 3 |

顺带一个收获：这次 5 条并发发送真的**触发了聚合阈值**（日志出现"原始消息 4 条，聚合后用户 1 个"），
这是之前手工单条发送从来做不到的。

### 验证二：强杀实验

用新用户，`docker pause` → 12 条消息 → `unpause` → 0.3s 后 `kill -9` → 重启：

| 时刻 | Redis | MySQL |
|---|---|---|
| kill 瞬间 | 12 | 无行 |
| 重启恢复后 | 12 | 12 |

**Redis 12 / MySQL 12，完全一致。** 改造前同样的操作是 17 / 24。
kill 前一级已经把 12 条聚合成 1 条消息发出，二级没来得及处理，重启后处理这一条，数字对得上。

### 残留：一级消费者仍不幂等

`CountFansConsumer` / `CountNoteLikeConsumer` 里写 Redis 用的是 `HINCRBY`，
一级消费者被重投时会把同一个增量再加一次，**Redis 会偏大**。

二级幂等只能保证「MySQL 不再比 Redis 多」，保证不了「Redis 一定等于真实事件数」。

要彻底闭合，一级也要按 msgId 去重。代价是一级消费者必须从 `RocketMQListener<String>` 改成
能拿到 `MessageExt` 的形式，BufferTrigger 的泛型也要跟着变，而且每条消息都要多一次 Redis/DB 判重
——这会削弱攒批带来的性能收益。当前判断：**收益不抵成本，先记在这里**。

面试时正确的说法是：
> 二级落库我做了 msgId 幂等，用一张幂等日志表和增量更新放在同一个本地事务里，重放不会重复累加。
> 但一级写 Redis 还是裸增量，被重投会偏大，这部分我知道没闭环，靠凌晨对账覆盖修正。

---

## 九、对账修复能力实测（之前只有设计推断，这次真跑了）

### 为什么必须测

前面所有结论都写着"靠凌晨对账修回来"——`CountReconciliationAuditService` 发现不一致就调
`repairService.repair()` 覆盖写。但这套逻辑**从来没有端到端跑过**：
`repair()` 能不能锁到行、`ensureCounterRow` 会不会提前建行、
`updateStoredCount` 会不会被别的路径覆盖回去、缓存失效到底删没删 key，全是推断。

被面试官问一句"你测过对账吗"就没法答。

### 实验设计

`FANS` 维度的对账链路：

```
XXL-JOB fansCountShardingJobHandler
  → selectMarkerTables('t_data_align_fans_count_temp_')   // 扫所有日期的标记表
  → 逐个标记实体：selectSourceCount(真实) vs selectStoredCount(计数表)
  → 不一致 → repair()：ensureCounterRow → FOR UPDATE 锁行 → 重算源表 → UPDATE 覆盖 → 提交后删 Redis key
```

造一个明确的偏差：

| 项 | 值 |
|---|---|
| 测试用户 | `8880001` |
| `t_following` 里真实粉丝数 | **3** 条 |
| 手工写成 `t_user_count.fans_total` | **99**（故意错） |
| Redis `count:user:8880001` | 也预热成 **99** |
| 今日 fans 标记表（`_20261020_1`） | 插一行 `user_id=8880001` |

标记表是必须的——**对账只处理"标记表里出现过的实体"**，没标记就等于没发生变更，扫不到。

### 触发方式

XXL-JOB 控制台可以点"执行一次"，也可以用 HTTP API（脚本化用这个更方便）：

```bash
# 1. 登录拿 cookie
curl -s -c /tmp/xxl_cookie.txt -X POST "http://127.0.0.1:7777/xxl-job-admin/login" \
  -H "Content-Type: application/x-www-form-urlencoded" -d "userName=admin&password=123456"

# 2. 触发任务（id=5 是 fansCountShardingJobHandler）
curl -s -b /tmp/xxl_cookie.txt -X POST "http://127.0.0.1:7777/xxl-job-admin/jobinfo/trigger" \
  -H "XXL-JOB-ACCESS-TOKEN: default_token" \
  -H "Content-Type: application/x-www-form-urlencoded" -d "id=5&executorParam=&addressList="
```

任务 id 对照：`3`=following、`4`=noteLike、`5`=fans、`6`=noteCollect、`7`=userCollect、`8`=userLike、`9`=notePublish。

### 结果

第一次跑：

```
WARN CountReconciliationAuditService : Count audit mismatch metric=FANS
     table=t_data_align_fans_count_temp_20261020_1 entityId=8880001 source=3 stored=99
INFO SupplementalCountReconciliationXxlJobs : FANS count reconciliation finished:
     tables=15, entities=1, mismatches=1, repaired=1
```

| 项 | 对账前 | 对账后 |
|---|---|---|
| `t_user_count.fans_total` | 99 ❌ | **3** ✅（等于源表真实值）|
| Redis `count:user:8880001` | 99 ❌ | **key 已删除**（缓存失效生效）|

第二次再跑：

```
FANS count reconciliation finished: tables=15, entities=1, mismatches=0, repaired=0
```

**已验证：修好的不会被重复修，对账本身是幂等的。**

### 这次测出来的两个边界

1. **对账靠标记表定位实体。** 标记没写进去（`createTableJobHandler` 漏跑、或写入失败），
   该实体就永远不会被扫到，偏差是永久的。这就是第七节那个启动自检为什么重要。
2. **只扫"被标记过的"实体，不是全表扫。** `entities=1` 说明它只处理了标记表里那一个用户，
   而不是 `t_user_count` 全表。这是设计上的成本控制，但也意味着**标记丢失 = 兜底失效**。

### 对简历与面试的意义

之前只能说"设计上对账会修"，现在可以说：

> 我手工把粉丝数从真实的 3 改成 99，触发一次对账任务，日志打出 `source=3 stored=99`，
> 然后计数表的行被覆盖回 3、Redis 缓存 key 也被删掉了；再跑一次是 `mismatches=0`，不会重复修。
> 但这套兜底有个前提：对账只处理日增量标记表里出现过的实体，标记丢了就扫不到，
> 所以我另外加了一个启动自检去保证当天的标记表一定存在。
