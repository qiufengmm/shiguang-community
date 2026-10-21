-- Durable recovery records for note content writes across MySQL and Cassandra.
-- Payload is kept only while an operation is unresolved, then scrubbed.
USE `xiaohashu`;

CREATE TABLE IF NOT EXISTS `t_note_content_saga` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `operation_id` varchar(64) NOT NULL,
  `note_id` bigint unsigned NOT NULL,
  `operation_type` varchar(16) NOT NULL COMMENT 'PUBLISH, UPDATE, CLEAR, or COMPENSATE',
  `content_uuid` varchar(36) DEFAULT NULL COMMENT 'Target UUID for writes or old UUID for cleanup',
  `payload` mediumtext DEFAULT NULL COMMENT 'Temporary recovery payload; scrubbed after convergence',
  `status` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, DELETE_PENDING, COMPENSATION_PENDING, COMPLETED, or ROLLED_BACK',
  `attempt_count` int unsigned NOT NULL DEFAULT 0,
  `next_attempt_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_error` varchar(1000) DEFAULT NULL,
  `lease_owner` varchar(64) DEFAULT NULL,
  `lease_until` datetime DEFAULT NULL,
  `lease_version` bigint unsigned NOT NULL DEFAULT 0,
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `completed_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_note_content_saga_operation_id` (`operation_id`),
  KEY `idx_note_content_saga_due` (`status`, `next_attempt_at`, `lease_until`, `id`),
  KEY `idx_note_content_saga_note` (`note_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Durable cross-store note content recovery';
