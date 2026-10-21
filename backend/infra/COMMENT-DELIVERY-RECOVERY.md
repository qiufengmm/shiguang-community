# 评论消息故障排查与恢复

评论发布和删除使用 MySQL outbox 重试投递；消费者将消息持久写入 MySQL inbox 后才向 RocketMQ 确认。Inbox 处理失败会保留记录并按封顶退避持续重试。`SENT` 只表示 RocketMQ 接收了消息，不能单独证明业务处理完成。

## 查看待处理记录

在 `xiaohashu` MySQL 库执行以下只读查询。不要查询或粘贴 `payload`：发布消息正文可能包含用户评论内容。

```sql
SELECT id, event_key, topic, status, attempt_count, next_attempt_at,
       lease_until, last_error, created_time, updated_time
FROM t_comment_outbox
WHERE status <> 'SENT'
ORDER BY created_time ASC
LIMIT 100;

SELECT id, event_key, event_type, status, attempt_count, next_attempt_at,
       lease_until, last_error, created_time, updated_time
FROM t_comment_inbox
WHERE status <> 'APPLIED'
ORDER BY created_time ASC
LIMIT 100;
```

`SENDING` 或 `PROCESSING` 且租约已过期的记录会被调度器重新领取；不要手动改状态。`PENDING` 会在 `next_attempt_at` 到期后重试。先根据 `last_error` 检查 MySQL、RocketMQ、KV/Cassandra 的可用性和服务日志，再让调度器继续处理。

## 检查已发送但尚未进入 Inbox 的消息

```sql
SELECT o.id, o.event_key, o.topic, o.sent_time, o.attempt_count, o.last_error
FROM t_comment_outbox o
LEFT JOIN t_comment_inbox i ON i.event_key = o.event_key
WHERE o.status = 'SENT'
  AND o.topic IN ('PublishCommentTopic', 'DeleteCommentTopic')
  AND i.id IS NULL
ORDER BY o.sent_time ASC
LIMIT 100;
```

查询结果表示 broker 已确认发送，但对应评论消费者尚未持久化 Inbox。先检查服务实例、消费者组和 RocketMQ 重试队列/DLQ。评论消费者组为 `xiaohashu_group_PublishCommentTopic` 和 `xiaohashu_group_DeleteCommentTopic`；只有 broker 尚无该组消费位点时，首次启动策略才会从首条消息开始消费积压消息。升级时必须保留现有组名和 broker 位点，不要删除、重建或重置消费组。

特别是删除消息：切勿为“补数据”而把 `xiaohashu_group_DeleteCommentTopic` 的位点重置到旧消息。旧版消费者可能已完成 MySQL 删除和计数扣减，但没有写入 Inbox；重放后新处理器无法仅凭旧消息判断此前副作用是否发生，可能再次扣减计数。确需重放时，先按 `t_comment`、删除墓碑和笔记计数核对受影响数据并安排对账，再在隔离环境演练后操作。

## RocketMQ DLQ 重投

只有消费者在把消息写入 Inbox 前持续失败时，消息才可能进入 RocketMQ 的消费重试或死信队列。使用当前 RocketMQ Dashboard 或管理员工具，按上述消费者组检查对应 DLQ，确认消息原始 topic、commentId 和失败原因。修复消费者或数据库故障后，将**原始消息正文**重新发布到原始 topic（`PublishCommentTopic` 或 `DeleteCommentTopic`），不要发布到 `%DLQ%...` topic。

重投时保留原始 commentId 和正文，以便 Inbox 唯一事件键去重。重复重投是允许的；切勿删除 `APPLIED` Inbox 或墓碑记录，也不要直接把失败记录改为 `APPLIED`。如果 Inbox 已存在但仍为 `PENDING`，它由 Inbox 调度器负责重试，不需要再从 RocketMQ 重投。

## 删除正文的安全边界

墓碑可以先于异步 SQL 删除落库。Cassandra 正文清理器只会选择其 `comment-delete:<delete_root_id>` Inbox 已经是 `APPLIED` 的墓碑，确保评论行、计数和删除后续事件先在同一 MySQL 事务完成。清理失败会保留 `PENDING` 状态并在下一轮重试。

## 保留要求

Inbox、已应用计数事件和删除墓碑参与去重及防止已删评论复活。不得手工清空或删除这些去重记录。

Outbox `SENT` 和 Inbox `APPLIED` 的消息正文最多保留 30 天；评论服务每小时分批清空超过 30 天的终态 `payload`，每轮最多处理 10,000 行。正文可能含用户评论，清空后仍永久保留 `event_key`、类型、主题、状态、时间和墓碑等精简去重信息。未完成的 `PENDING`、`SENDING`、`PROCESSING` 记录不受清理影响。终态记录正文被清空后，重复投递仍按稳定事件键去重，但不能再从该行恢复原消息正文；需要重投时应从 RocketMQ DLQ 或备份获取原文。

整库备份可能包含被清理前的消息正文。当前本机备份脚本不会自动过期或删除备份；备份保留上限按 30 天执行，到期后由管理员逐个核对并删除明确的过期备份目录，不能使用批量删除命令。恢复演练如需长期保留，应作为受限访问的归档单独管理，并同步调整评论正文保留要求。
