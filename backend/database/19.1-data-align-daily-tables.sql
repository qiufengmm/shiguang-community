-- 第 19.1 节：数据对齐日增量表，日期和分片使用教程示例。

CREATE TABLE `t_data_align_following_count_temp_20241102_0` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据对齐日增量表：关注数';

CREATE TABLE `t_data_align_fans_count_temp_20241102_0` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据对齐日增量表：粉丝数';

CREATE TABLE `t_data_align_note_collect_count_temp_20241102_0` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `note_id` bigint unsigned NOT NULL COMMENT '笔记ID',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_note_id` (`note_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据对齐日增量表：笔记获得收藏数';

CREATE TABLE `t_data_align_user_collect_count_temp_20241102_0` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据对齐日增量表：用户获得收藏数';

CREATE TABLE `t_data_align_user_like_count_temp_20241102_0` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据对齐日增量表：用户获得点赞数';

CREATE TABLE `t_data_align_note_like_count_temp_20241102_0` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `note_id` bigint unsigned NOT NULL COMMENT '笔记ID',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_note_id` (`note_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据对齐日增量表：笔记获得点赞数';

CREATE TABLE `t_data_align_note_publish_count_temp_20241102_0` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `note_id` bigint unsigned NOT NULL COMMENT '笔记ID',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_note_id` (`note_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据对齐日增量表：用户发布笔记数';
