select u.id, u.nickname, u.avatar, u.xiaohashu_id, uc.note_total, uc.fans_total,
DATE_FORMAT(u.update_time, '%Y-%m-%d %H:%i:%s') AS update_time
from t_user u left join t_user_count uc on u.id = uc.user_id
where u.`status` = 0 and u.is_deleted = 0
and u.update_time > :sql_last_value
order by u.id
