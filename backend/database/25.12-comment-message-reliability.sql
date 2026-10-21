-- Comment reliable delivery/inbox and deletion tombstones.
-- Keep APPLIED inbox rows and tombstones: they prevent redelivery from duplicating or resurrecting data.
USE `xiaohashu`;

CREATE TABLE IF NOT EXISTS `t_comment_outbox` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `event_key` varchar(191) NOT NULL,
  `topic` varchar(128) NOT NULL,
  `payload` longtext NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING',
  `attempt_count` int unsigned NOT NULL DEFAULT 0,
  `next_attempt_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_error` varchar(1000) DEFAULT NULL,
  `lease_owner` varchar(64) DEFAULT NULL,
  `lease_until` datetime DEFAULT NULL,
  `lease_version` bigint unsigned NOT NULL DEFAULT 0,
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `sent_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_comment_outbox_event_key` (`event_key`),
  KEY `idx_comment_outbox_due` (`status`, `next_attempt_at`, `id`),
  KEY `idx_comment_outbox_lease` (`status`, `lease_until`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论事务发件箱';

CREATE TABLE IF NOT EXISTS `t_comment_inbox` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `event_key` varchar(191) NOT NULL,
  `event_type` varchar(32) NOT NULL,
  `payload` longtext NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING',
  `attempt_count` int unsigned NOT NULL DEFAULT 0,
  `next_attempt_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_error` varchar(1000) DEFAULT NULL,
  `lease_owner` varchar(64) DEFAULT NULL,
  `lease_until` datetime DEFAULT NULL,
  `lease_version` bigint unsigned NOT NULL DEFAULT 0,
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `applied_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_comment_inbox_event_key` (`event_key`),
  KEY `idx_comment_inbox_due` (`status`, `next_attempt_at`, `id`),
  KEY `idx_comment_inbox_lease` (`status`, `lease_until`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论事务收件箱';

CREATE TABLE IF NOT EXISTS `t_comment_tombstone` (
  `comment_id` bigint unsigned NOT NULL,
  `delete_root_id` bigint unsigned NOT NULL,
  `note_id` bigint unsigned NOT NULL,
  `level` tinyint NOT NULL,
  `parent_id` bigint unsigned NOT NULL,
  `content_uuid` varchar(36) DEFAULT NULL,
  `is_content_empty` bit(1) NOT NULL DEFAULT b'0',
  `create_time` datetime NOT NULL,
  `content_cleanup_status` varchar(16) NOT NULL DEFAULT 'PENDING',
  `deleted_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`comment_id`),
  KEY `idx_comment_tombstone_delete_root` (`delete_root_id`, `comment_id`),
  KEY `idx_comment_tombstone_cleanup` (`content_cleanup_status`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论删除墓碑与正文清理任务';

CREATE TABLE IF NOT EXISTS `t_comment_count_event` (
  `event_id` varchar(191) NOT NULL,
  `counter_type` varchar(32) NOT NULL,
  `comment_id` bigint unsigned NOT NULL,
  `note_id` bigint unsigned NOT NULL,
  `parent_id` bigint unsigned DEFAULT NULL,
  `applied_delta` tinyint NOT NULL DEFAULT 0,
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`, `counter_type`),
  KEY `idx_comment_count_event_comment` (`comment_id`, `counter_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论计数事件幂等记录';
