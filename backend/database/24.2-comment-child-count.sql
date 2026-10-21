-- 第 24.2 节：冗余一级评论的二级评论总数
USE `xiaohashu`;

ALTER TABLE `t_comment`
    ADD COLUMN `child_comment_total` bigint(20) unsigned DEFAULT '0'
    COMMENT '二级评论总数（只有一级评论才需要统计）';
