-- Durable note-operation delivery and idempotent note-publish counting.
-- Apply this migration before deploying the updated note and count services.
USE `xiaohashu`;

CREATE TABLE IF NOT EXISTS `t_note_publish_count_lock` (
  `user_id` bigint unsigned NOT NULL COMMENT 'creator ID used to serialize note writes, count events, and reconciliation',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Per-creator note publish count lock';

CREATE TABLE IF NOT EXISTS `t_note_operate_outbox` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `event_id` varchar(160) NOT NULL,
  `creator_id` bigint unsigned NOT NULL,
  `note_id` bigint unsigned NOT NULL,
  `operation_type` tinyint NOT NULL COMMENT '0 delete, 1 publish, 2 cache invalidation',
  `topic` varchar(160) NOT NULL,
  `payload` mediumtext NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING',
  `attempt_count` int unsigned NOT NULL DEFAULT 0,
  `next_attempt_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_error` varchar(1000) DEFAULT NULL,
  `lease_owner` varchar(64) DEFAULT NULL,
  `lease_until` datetime DEFAULT NULL,
  `lease_version` bigint unsigned NOT NULL DEFAULT 0,
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `sent_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_id` (`event_id`),
  KEY `idx_status_due` (`status`, `next_attempt_at`, `id`),
  KEY `idx_creator_id` (`creator_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Transactional outbox for note publish/delete events';

CREATE TABLE IF NOT EXISTS `t_note_publish_count_event` (
  `event_id` varchar(160) NOT NULL,
  `user_id` bigint unsigned NOT NULL,
  `note_id` bigint unsigned NOT NULL,
  `operation_type` tinyint NOT NULL COMMENT '0 delete, 1 publish',
  `applied_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`),
  KEY `idx_user_id_applied_time` (`user_id`, `applied_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Idempotency ledger for note publish count events';
