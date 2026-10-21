-- 第 25.16 节后续可靠性修复：给每个正文 UUID 加永久删除围栏。
-- 旧行 is_deleted 为 NULL 时仍按未删除的正文读取；禁止给此列添加 TTL。
ALTER TABLE xiaohashu.note_content ADD IF NOT EXISTS is_deleted boolean;
