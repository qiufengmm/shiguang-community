# 计数服务恢复 BufferTrigger 聚合 — 改动方案

> 状态：待确认，未动代码。
> 目标：把 `50ae42c` 引入的「源表重算」换回「BufferTrigger 聚合 + 增量落库」，恢复高吞吐写能力，同时把数据丢失的兜底链路写清楚。

---

## 1. 为什么改回

现状 `CountSourceRefreshService` 每条消息做一次 `COUNT` 覆盖写，复杂度 O(n)，n 是实体数据量：

| 方法 | SQL | 复杂度 |
|---|---|---|
| `selectUserLikeTotal` | `t_note_like JOIN t_note WHERE n.creator_id = ?` | O(作者历史总获赞)，无上限 |
| `selectFansTotal` | `COUNT(*) t_following WHERE following_user_id = ?` | O(粉丝数) |

数据量一涨就线性变慢，且无法通过加机器缓解（同一条 SQL 本身贵）。

BufferTrigger 聚合把成本压回 O(1)：1000 条消息合并成一批、按 userId 求和后一次批量 `UPDATE`，写库次数从 N 降到 N/1000。

代价是进程内缓冲有丢失窗口，靠第 6 节的兜底链路覆盖。

---

## 2. 恢复范围（照教程的真实区分，不是四个全上）

`git grep BufferTrigger 50ae42c^` 的结果：教程原版只有 3 个消费者用了聚合。

| 消费者 | Topic | 聚合 | 依据 |
|---|---|---|---|
| `CountFansConsumer` 粉丝数 | `TOPIC_COUNT_FANS` | 用 | 单个大 V 短时间涨粉量极大 |
| `CountNoteLikeConsumer` 笔记点赞 | `TOPIC_COUNT_NOTE_LIKE` | 用 | 爆款笔记点赞峰值高 |
| `CountNoteCollectConsumer` 笔记收藏 | `TOPIC_COUNT_NOTE_COLLECT` | 用 | 同上 |
| `CountFollowingConsumer` 关注数 | `TOPIC_COUNT_FOLLOWING` | **不用**，维持重算 | 教程原注释：单个用户无法短时间内关注大量用户，所以无需聚合 |

关注数维持现状的原因：单个用户的关注数业务上有界（千级），`COUNT(*) WHERE user_id = ?` 走索引扫几千行，可接受。

---

## 3. 目标链路

```
业务 MQ（同一条消息，两个独立消费组各拉一份）
  │
  ├─ 组 xiaohashu_group_COUNT_FANS
  │     └→ CountFansConsumer.onMessage()
  │          └→ bufferTrigger.enqueue(body)      ← 立即 return，ACK
  │               └→ [攒够 1000 条 / 1s] consumeMessage(List<String>)
  │                    ├→ 按 targetUserId 分组求和（FOLLOW +1 / UNFOLLOW -1）
  │                    ├→ Redis HINCRBY（若 hash 已存在）
  │                    └→ asyncSend TOPIC_COUNT_FANS_2_DB（聚合后的 Map<userId, delta>）
  │                          └→ CountFans2DBConsumer  ── RateLimiter(5000/s)
  │                               └→ insertOrUpdateFansTotalByUserId(delta, userId)
  │                                    fans_total = fans_total + delta
  │
  └─ 组 xiaohashu_group_data_align_COUNT_FANS
        └→ TodayUserFollowIncrementData2DBConsumer  → 写日增量标记表
                                                        │
                                              凌晨 XXL-JOB 分片对账
                                              source ≠ stored → repair() 覆盖写
```

---

## 4. 逐文件改动

### 4.1 pom — 无需改动

- 根 `pom.xml:346` 已有 `buffer-trigger` 版本管理（0.2.21）
- `xiaohashu-count-biz/pom.xml:120` 已有依赖

### 4.2 `CountFansConsumer`

替换 `CountSourceRefreshService` 为 BufferTrigger（骨架，与教程一致）：

```java
private final BufferTrigger<String> bufferTrigger = BufferTrigger.<String>batchBlocking()
        .bufferSize(50000)
        .batchSize(1000)
        .linger(Duration.ofSeconds(1))
        .setConsumerEx(this::consumeMessage)
        .build();

@Override
public void onMessage(String body) {
    bufferTrigger.enqueue(body);
}

private void consumeMessage(List<String> bodys) {
    // 1. 解析 + 按 targetUserId 分组
    // 2. 组内按 type 求和（FOLLOW +1 / UNFOLLOW -1），剔除净额为 0 的 key
    // 3. 若净额为 0 直接跳过（不发 MQ、不更新 Redis）
    // 4. Redis HINCRBY（先 hasKey 判断，避免为无缓存实体造缓存）
    // 5. asyncSend 到 TOPIC_COUNT_FANS_2_DB
}

@PreDestroy
void flushOnShutdown() {
    bufferTrigger.close();   // close() 内部会 manuallyDoTrigger()，见 6.1
}
```

**净额为 0 必须跳过**：同一批里同一个用户先关注又取关，delta = 0，此时若仍发 MQ，2DB 会执行 `+0` 的无用更新。

### 4.3 `CountNoteLikeConsumer` / `CountNoteCollectConsumer`

结构同上，分组 key 换成 `noteId`，Redis 字段换成 `like_total` / `collect_total`，2DB topic 对应 `TOPIC_COUNT_NOTE_LIKE_2_DB` / `TOPIC_COUNT_NOTE_COLLECT_2_DB`。

注意这两个维度**同时影响两处计数**：笔记的 `like_total` 和作者的 `like_total`。教程原版在 2DB 消费者里各更新一次，改动时两处都要保留。

### 4.4 2DB 消费者（4 个）

从 `countSourceRefreshService.refreshXxx(id)` 改回增量写：

```java
// 现状
countSourceRefreshService.refreshFans(userId);

// 改回
userCountDOMapper.insertOrUpdateFansTotalByUserId(delta, userId);
```

Mapper 方法都还在，不用新增 SQL：

- `UserCountDOMapper.xml:123` `insertOrUpdateFansTotalByUserId`
- `UserCountDOMapper.xml:133` `insertOrUpdateLikeTotalByUserId`
- `UserCountDOMapper.xml:138` `insertOrUpdateCollectTotalByUserId`
- `NoteCountDOMapper.xml` 对应的笔记维度方法

### 4.5 保留不动

`CountSourceRefreshService` + `CountSourceRefreshMapper` **不删**。凌晨对账的 `CountReconciliationRepairService` 复用它做覆盖写。

`CountFollowingConsumer` 维持现有重算实现。

---

## 5. 数据丢失窗口（精确描述）

| 项 | 值 |
|---|---|
| ACK 时机 | `onMessage` 中 `enqueue()` 返回后，Spring 容器向 broker 返回 CONSUME_SUCCESS，位点前移 |
| 此时数据在哪 | 进程内 `LinkedBlockingQueue` |
| 最大暴露量 | `bufferSize` 上限 50000 条，或 `linger` 1 秒内的量 |
| 触发丢失 | `kill -9`、OOM Kill、容器 `--force` 删除、宿主机断电 |

**教程原版的一个额外漏洞**：`bufferTrigger` 是 `new` 出来的普通成员字段，不是 Spring Bean，所以容器不会调用它的 `close()`。这意味着**即使正常发版重启（SIGTERM）也会丢**，不只是强杀。

这一点必须在改动里补掉，见 6.1。

---

## 6. 必须补的两件事

### 6.1 `@PreDestroy` 优雅刷盘

`BufferTrigger` 源码（0.2.21）中 `SimpleBufferTrigger.close()` 的实现：

```java
@Override
public void close() {
    shutdown = true;
    try {
        manuallyDoTrigger();   // 强制触发一次消费，清空缓存
    } finally {
        shutdownExecutor.run();
    }
}
```

所以加上 `@PreDestroy` 就能覆盖 SIGTERM（正常发版、`docker stop`）这一大类场景。**覆盖不了 `kill -9` 和 OOM。**

### 6.2 日增量表 + 凌晨对账兜底（项目已有，只需确认）

这是本项目能选 BufferTrigger 的前提，链路已经存在：

1. **独立的消费组**。`TodayUserFollowIncrementData2DBConsumer` 订阅的是**同一个** `TOPIC_COUNT_FOLLOWING`，但消费组前缀是 `xiaohashu_group_data_align_`。RocketMQ 每个消费组独立维护位点，所以计数服务丢的那条消息，data-align 这一侧照常收到。

   | data-align 消费者 | 订阅 topic |
   |---|---|
   | `TodayUserFollowIncrementData2DBConsumer` | `TOPIC_COUNT_FOLLOWING`（同时写关注数和粉丝数两张标记表） |
   | `TodayNoteLikeIncrementData2DBConsumer` | `TOPIC_COUNT_NOTE_LIKE` |
   | `TodayNoteCollectIncrementData2DBConsumer` | `TOPIC_COUNT_NOTE_COLLECT` |
   | `TodayNotePublishIncrementData2DBConsumer` | `TOPIC_NOTE_OPERATE` |

2. **7 个指标全覆盖**。`CountAuditMetric` 枚举：`FOLLOWING` / `FANS` / `NOTE_COLLECT` / `USER_COLLECT` / `USER_LIKE` / `NOTE_LIKE` / `NOTE_PUBLISH`。

3. **对账是"修"不只是"报"**。`CountReconciliationAuditService.audit()` 发现 `sourceCount != storedCount` 时直接调 `repairService.repair(metric, entityId)` 覆盖写，不是只打日志。

结论：BufferTrigger 丢的那批，会在**次日凌晨**被重算修正。是最终一致，不是丢失不管。

---

## 7. 残留风险（面试必须承认，不能只讲优点）

| 风险 | 后果 | 现状 |
|---|---|---|
| 强杀丢一批 | 计数偏小，累加语义下不会自动恢复 | T+1 对账修 |
| 重复消费 | 计数偏大（`+delta` 非幂等，无去重表） | T+1 对账修 |
| 白天不一致窗口 | 最多 24 小时内用户看到错误数字 | **未解决** |
| 日增量标记本身丢 | 对账扫不到该实体 → 永久偏差 | RocketMQ 同步发送 + broker 持久化，概率低 |
| 净额为 0 未跳过 | 无谓更新 + Redis 空转 | 改动中规避 |

如果面试官追问"重复消费怎么办"，正确回答是**承认没有去重表，靠对账覆盖**，不要说"MQ 保证不重复"。

---

## 8. 验证步骤

1. **单元测试**：分组正负号（FOLLOW/UNFOLLOW 混合批次）、净额 0 跳过、`@PreDestroy` 触发 flush。
2. **冒烟**：关注/取关各 100 次 → 核对 Redis hash 与 `t_user_count.fans_total` 一致。
3. **幂等实验**：同一条消息手工重投 3 次 → 记录计数多加多少 → 跑一次对账 → 确认修回。这一步的数据就是面试素材。
4. **强杀实验**：压 5000 条后立刻 `kill -9` → 统计丢失条数。**这是"丢多少"的唯一真实数字，比任何推断都可靠。**
5. **压测**：2DB 消费者链路 TPS，才能往简历写数字。

---

## 9. 简历措辞（改回教程版）

```
- **计数服务**：消费关注、点赞、收藏等行为 MQ，在消费者中用 BufferTrigger 做流量聚合
  （1000 条一批、1 秒一次），先写 Redis 再异步削峰落库，支撑高并发写；
  XXL-JOB 预建日增量变更表，用分片广播并行执行对齐任务，兜住缓冲区的数据丢失。
```

（最后半句是自己加的兜底说明，教程没有。加它是因为：说了 BufferTrigger，面试官下一句必然问丢数据。）

---

## 10. 面试话术：数据丢了怎么办

三层递进，逐层加深：

> **第一层 — 承认。** BufferTrigger 是进程内缓冲，消息 enqueue 之后就 ACK 了，进程被 kill 或者 OOM 的时候，缓冲区里还没攒够一批的那部分会丢，而且因为计数是累加语义，丢了不会自动恢复。
>
> **第二层 — 缓解。** 我给 BufferTrigger 加了 `@PreDestroy` 调 `close()`，它内部会强制触发一次消费刷盘，所以正常发版、`docker stop` 这类有停机流程的场景不会丢。覆盖不了的只有 `kill -9`、OOM 和断电。
>
> **第三层 — 兜底。** 计数服务丢的那条消息，`data-align` 服务是另一个独立消费组，订阅同一个 topic、自己维护位点，所以它照常收到并写入日增量标记表。凌晨 XXL-JOB 分片对账会扫标记表里的实体，比对源关系表重算，不一致就直接覆盖写。7 个计数维度都覆盖了。所以最坏情况是**当天数字偏了，第二天凌晨修回来**，不是永久错。

如果继续追问"那白天用户看到错的数字不管吗"：

> 管不了，这是这个方案的代价。要彻底解决就得先落盘再 ACK——消息进来先写一张 inbox 表再返回，后台线程从 inbox 攒批。这样不丢，但每条消息多一次写，攒批省下的写压力还回去一部分。我当时优先选了吞吐，把一致性放在了对账层。

---

## 11. 落地顺序

1. 改 3 个聚合消费者（含 `@PreDestroy`）
2. 改 4 个 2DB 消费者为增量写
3. 跑 count 模块单元测试（当前 30 项）
4. 冒烟 + 幂等实验
5. 强杀实验，拿到真实丢失数据
6. 写链路压测，拿到 TPS
7. 简历措辞按第 9 节替换
