-- 第 17.1 节：计数服务表结构
-- 依据教程：计数服务职责说明、原型图分析、表设计

CREATE TABLE `t_note_like` (
  `id` bigint(11) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint(11) NOT NULL COMMENT '用户ID',
  `note_id` bigint(11) NOT NULL COMMENT '笔记ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `status` tinyint(2) NOT NULL DEFAULT '0' COMMENT '点赞状态(0：取消点赞 1：点赞)',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_id_note_id` (`user_id`,`note_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='笔记点赞表';

CREATE TABLE `t_note_collection` (
  `id` bigint(11) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint(11) NOT NULL COMMENT '用户ID',
  `note_id` bigint(11) NOT NULL COMMENT '笔记ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `status` tinyint(2) NOT NULL DEFAULT '0' COMMENT '收藏状态(0：取消收藏 1：收藏)',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_id_note_id` (`user_id`,`note_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='笔记收藏表';

CREATE TABLE `t_note_count` (
  `id` bigint(11) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `note_id` bigint(11) unsigned NOT NULL COMMENT '笔记ID',
  `like_total` bigint(11) DEFAULT '0' COMMENT '获得点赞总数',
  `collect_total` bigint(11) DEFAULT '0' COMMENT '获得收藏总数',
  `comment_total` bigint(11) DEFAULT '0' COMMENT '被评论总数',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_note_id` (`note_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='笔记计数表';

CREATE TABLE `t_user_count` (
  `id` bigint(11) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint(11) unsigned NOT NULL COMMENT '用户ID',
  `fans_total` bigint(11) DEFAULT '0' COMMENT '粉丝总数',
  `following_total` bigint(11) DEFAULT '0' COMMENT '关注总数',
  `note_total` bigint(11) DEFAULT '0' COMMENT '发布笔记总数',
  `like_total` bigint(11) DEFAULT '0' COMMENT '获得点赞总数',
  `collect_total` bigint(11) DEFAULT '0' COMMENT '获得收藏总数',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户计数表';
