-- Guarded one-user reconciliation. Set @user_id explicitly before running.
-- The updated note writers, count consumer, and this script all lock the same
-- per-creator row, so a concurrent publish/delete or count delivery is ordered
-- against this snapshot. Do not run before both updated services are deployed.
-- Before running, verify zero lag/inflight on NoteOperateTopic and its retry
-- topic for xiaohashu_group_NoteOperateTopic, and confirm there are no DLQ
-- messages for that group. New note writes must all use the transactional outbox.
USE `xiaohashu`;

SET @user_id := 100;
START TRANSACTION;

INSERT IGNORE INTO t_note_publish_count_lock (user_id) VALUES (@user_id);
SELECT user_id
FROM t_note_publish_count_lock
WHERE user_id = @user_id
FOR UPDATE;

SET @stored_count := (
  SELECT COALESCE(note_total, 0)
  FROM t_user_count
  WHERE user_id = @user_id
);
SET @source_count := (
  SELECT COUNT(*)
  FROM t_note
  WHERE creator_id = @user_id
    AND status = 1
);

-- Messages already represented in the source snapshot are marked as applied,
-- so a delayed outbox delivery after this transaction cannot add them again.
INSERT IGNORE INTO t_note_publish_count_event
  (event_id, user_id, note_id, operation_type, applied_time)
SELECT event_id, creator_id, note_id, operation_type, NOW()
FROM t_note_operate_outbox
WHERE creator_id = @user_id;

INSERT INTO t_user_count (user_id, note_total)
VALUES (@user_id, @source_count)
ON DUPLICATE KEY UPDATE note_total = VALUES(note_total);

SELECT @user_id AS user_id,
       @stored_count AS stored_before,
       @source_count AS source_count,
       note_total AS stored_after
FROM t_user_count
WHERE user_id = @user_id;

COMMIT;
