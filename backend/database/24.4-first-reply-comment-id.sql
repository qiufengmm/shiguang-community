ALTER TABLE t_comment
    ADD COLUMN first_reply_comment_id BIGINT(20) UNSIGNED DEFAULT 0 COMMENT '最早回复的评论ID (只有一级评论需要)';
