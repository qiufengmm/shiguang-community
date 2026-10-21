-- Idempotency ledger for the second-level count consumers (the "*2DBConsumer" classes).
--
-- Background: those consumers apply a net delta with `x = x + delta`, which is NOT idempotent.
-- When RocketMQ redelivers a message whose offset was not committed before a crash, the delta
-- is applied twice and MySQL drifts above the Redis counters. The ledger below records the
-- RocketMQ message id in the SAME local transaction as the delta update, so a redelivery finds
-- the row already present and skips the whole batch atomically.
--
-- Apply this migration before deploying the updated count service.
USE `xiaohashu`;

CREATE TABLE IF NOT EXISTS `t_count_consume_log` (
  `msg_id` varchar(64) NOT NULL COMMENT 'RocketMQ message id; primary idempotency key',
  `consumer_group` varchar(128) NOT NULL COMMENT 'consumer group, so one topic can serve several groups',
  `topic` varchar(128) NOT NULL,
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`msg_id`, `consumer_group`),
  KEY `idx_created_time` (`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Idempotency ledger for second-level count consumers';
