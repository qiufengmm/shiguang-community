select n.id, n.title, n.topic_name as topic, n.type, n.img_uris,
DATE_FORMAT(n.create_time, '%Y-%m-%d %H:%i:%s') AS create_time,
DATE_FORMAT(n.update_time, '%Y-%m-%d %H:%i:%s') AS update_time,
u.nickname as creator_nickname, u.avatar as creator_avatar, nc.like_total, nc.collect_total, nc.comment_total
from t_note n left join t_user u on n.creator_id = u.id
left join t_note_count nc on n.id = nc.note_id
where n.visible = 0 and n.`status` = 1
and (n.update_time > :sql_last_value or u.update_time > :sql_last_value)
order by n.id
