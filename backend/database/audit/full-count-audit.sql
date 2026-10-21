-- Read-only historical count audit for the xiaohashu schema.
-- Run inside one REPEATABLE READ, READ ONLY transaction.
-- Keep IDs as text in the result so clients cannot round Snowflake IDs.
WITH user_entities AS (
    SELECT id AS entity_id FROM t_user
    UNION
    SELECT user_id AS entity_id FROM t_user_count
    UNION
    SELECT creator_id AS entity_id FROM t_note
    UNION
    SELECT user_id AS entity_id FROM t_following
    UNION
    SELECT following_user_id AS entity_id FROM t_following
),
note_entities AS (
    SELECT id AS entity_id FROM t_note
    UNION
    SELECT note_id AS entity_id FROM t_note_count
    UNION
    SELECT note_id AS entity_id FROM t_note_like
    UNION
    SELECT note_id AS entity_id FROM t_note_collection
    UNION
    SELECT note_id AS entity_id FROM t_comment
),
user_metric_rows AS (
    SELECT 'FOLLOWING' AS metric, e.entity_id,
           (u.id IS NOT NULL) AS entity_exists, COUNT(f.id) AS source_count,
           c.following_total AS stored_count, (c.user_id IS NOT NULL) AS counter_row_present
    FROM user_entities e
    LEFT JOIN t_user u ON u.id = e.entity_id
    LEFT JOIN t_user_count c ON c.user_id = e.entity_id
    LEFT JOIN t_following f ON f.user_id = e.entity_id
    GROUP BY e.entity_id, u.id, c.user_id, c.following_total
    UNION ALL
    SELECT 'FANS', e.entity_id, (u.id IS NOT NULL), COUNT(f.id),
           c.fans_total, (c.user_id IS NOT NULL)
    FROM user_entities e
    LEFT JOIN t_user u ON u.id = e.entity_id
    LEFT JOIN t_user_count c ON c.user_id = e.entity_id
    LEFT JOIN t_following f ON f.following_user_id = e.entity_id
    GROUP BY e.entity_id, u.id, c.user_id, c.fans_total
    UNION ALL
    SELECT 'USER_LIKE', e.entity_id, (u.id IS NOT NULL), COUNT(l.id),
           c.like_total, (c.user_id IS NOT NULL)
    FROM user_entities e
    LEFT JOIN t_user u ON u.id = e.entity_id
    LEFT JOIN t_user_count c ON c.user_id = e.entity_id
    LEFT JOIN t_note n ON n.creator_id = e.entity_id
    LEFT JOIN t_note_like l ON l.note_id = n.id AND l.status = 1
    GROUP BY e.entity_id, u.id, c.user_id, c.like_total
    UNION ALL
    SELECT 'USER_COLLECT', e.entity_id, (u.id IS NOT NULL), COUNT(cn.id),
           uc.collect_total, (uc.user_id IS NOT NULL)
    FROM user_entities e
    LEFT JOIN t_user u ON u.id = e.entity_id
    LEFT JOIN t_user_count uc ON uc.user_id = e.entity_id
    LEFT JOIN t_note n ON n.creator_id = e.entity_id
    LEFT JOIN t_note_collection cn ON cn.note_id = n.id AND cn.status = 1
    GROUP BY e.entity_id, u.id, uc.user_id, uc.collect_total
    UNION ALL
    SELECT 'NOTE_PUBLISH', e.entity_id, (u.id IS NOT NULL), COUNT(n.id),
           c.note_total, (c.user_id IS NOT NULL)
    FROM user_entities e
    LEFT JOIN t_user u ON u.id = e.entity_id
    LEFT JOIN t_user_count c ON c.user_id = e.entity_id
    LEFT JOIN t_note n ON n.creator_id = e.entity_id AND n.status <> 2
    GROUP BY e.entity_id, u.id, c.user_id, c.note_total
),
note_metric_rows AS (
    SELECT 'NOTE_LIKE' AS metric, e.entity_id,
           (n.id IS NOT NULL) AS entity_exists, COUNT(l.id) AS source_count,
           c.like_total AS stored_count, (c.note_id IS NOT NULL) AS counter_row_present
    FROM note_entities e
    LEFT JOIN t_note n ON n.id = e.entity_id
    LEFT JOIN t_note_count c ON c.note_id = e.entity_id
    LEFT JOIN t_note_like l ON l.note_id = e.entity_id AND l.status = 1
    GROUP BY e.entity_id, n.id, c.note_id, c.like_total
    UNION ALL
    SELECT 'NOTE_COLLECT', e.entity_id, (n.id IS NOT NULL), COUNT(cn.id),
           c.collect_total, (c.note_id IS NOT NULL)
    FROM note_entities e
    LEFT JOIN t_note n ON n.id = e.entity_id
    LEFT JOIN t_note_count c ON c.note_id = e.entity_id
    LEFT JOIN t_note_collection cn ON cn.note_id = e.entity_id AND cn.status = 1
    GROUP BY e.entity_id, n.id, c.note_id, c.collect_total
    UNION ALL
    SELECT 'NOTE_COMMENT', e.entity_id, (n.id IS NOT NULL), COUNT(cm.id),
           c.comment_total, (c.note_id IS NOT NULL)
    FROM note_entities e
    LEFT JOIN t_note n ON n.id = e.entity_id
    LEFT JOIN t_note_count c ON c.note_id = e.entity_id
    LEFT JOIN t_comment cm ON cm.note_id = e.entity_id
    GROUP BY e.entity_id, n.id, c.note_id, c.comment_total
),
all_metric_rows AS (
    SELECT * FROM user_metric_rows
    UNION ALL
    SELECT * FROM note_metric_rows
)
SELECT metric,
       CAST(entity_id AS CHAR) AS entity_id,
       source_count,
       stored_count,
       counter_row_present,
       CASE
           WHEN entity_exists = 0 THEN 'ORPHAN_ENTITY'
           WHEN counter_row_present = 0 AND source_count = 0 THEN 'ZERO_ROW_ABSENT'
           WHEN counter_row_present = 0 THEN 'MISSING_COUNTER'
           WHEN stored_count IS NULL THEN 'NULL_COUNTER'
           WHEN stored_count < 0 THEN 'NEGATIVE_COUNTER'
           WHEN stored_count <> source_count THEN 'MISMATCH'
           ELSE 'MATCH'
       END AS status
FROM all_metric_rows
ORDER BY metric, entity_id;
