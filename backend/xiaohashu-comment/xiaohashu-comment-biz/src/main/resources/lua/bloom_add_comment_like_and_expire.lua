-- 操作的 Key
local key = KEYS[1]
local commentId = ARGV[1] -- 评论 ID
local expireSeconds = ARGV[2] -- 过期时间（秒）

redis.call("BF.ADD", key, commentId)
redis.call("EXPIRE", key, expireSeconds)
return 0
