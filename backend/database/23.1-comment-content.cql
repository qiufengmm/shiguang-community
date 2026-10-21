-- 第 23.1 节：Cassandra 评论正文表结构
-- 依据教程：评论服务职责说明、原型图分析、表设计

USE xiaohashu;

CREATE TABLE comment_content (
    note_id BIGINT,
    year_month TEXT,
    content_id UUID,
    content TEXT,
    PRIMARY KEY ((note_id, year_month), content_id)
);
