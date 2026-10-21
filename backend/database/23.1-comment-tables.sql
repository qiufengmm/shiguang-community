-- 第 23.1 节：评论服务表结构
-- 依据教程：评论服务职责说明、原型图分析、表设计

USE `xiaohashu`;

CREATE TABLE `t_comment` (
    `id` bigint (20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
    `note_id` bigint (20) unsigned NOT NULL COMMENT '关联的笔记ID',
    `user_id` bigint (20) unsigned NOT NULL COMMENT '发布者用户ID',
    `content_uuid` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '评论内容UUID',
    `is_content_empty` bit(1) NOT NULL DEFAULT b'0' COMMENT '内容是否为空(0：不为空 1：为空)',
    `image_url` varchar(60) NOT NULL DEFAULT '' COMMENT '评论附加图片URL',
    `level` tinyint (2) NOT NULL DEFAULT '1' COMMENT '级别(1：一级评论 2：二级评论)',
    `reply_total` bigint (20) unsigned DEFAULT 0 COMMENT '评论被回复次数，仅一级评论需要',
    `like_total` bigint (20) DEFAULT 0 COMMENT '评论被点赞次数',
    `parent_id` bigint (20) unsigned DEFAULT 0 COMMENT '父ID (若是对笔记的评论，则此字段存储笔记ID; 若是二级评论，则此字段存储一级评论的ID)',
    `reply_comment_id` bigint (20) unsigned DEFAULT 0 COMMENT '回复哪个的评论 (0表示是对笔记的评论，若是对他人评论的回复，则存储回复评论的ID)',
    `reply_user_id` bigint (20) unsigned DEFAULT 0 COMMENT '回复的哪个用户, 存储用户ID',
    `is_top` tinyint (2) NOT NULL DEFAULT '0' COMMENT '是否置顶(0：不置顶 1：置顶)',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_note_id` (`note_id`) USING BTREE,
    KEY `idx_user_id` (`user_id`) USING BTREE,
    KEY `idx_parent_id` (`parent_id`) USING BTREE,
    KEY `idx_create_time` (`create_time`) USING BTREE,
    KEY `idx_reply_comment_id` (`reply_comment_id`) USING BTREE,
    KEY `idx_reply_user_id` (`reply_user_id`) USING BTREE
) ENGINE=InnoDB COMMENT='评论表';

CREATE TABLE `t_comment_like` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `comment_id` bigint NOT NULL COMMENT '评论ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id_comment_id` (`user_id`,`comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论点赞表';
