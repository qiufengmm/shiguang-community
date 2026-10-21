-- Lua 脚本：点赞布隆过滤器

local key = KEYS[1]
local noteId = ARGV[1]

-- 检查布隆过滤器是否存在
local exists = redis.call('EXISTS', key)
if exists == 0 then
    return -1
end

-- 检查笔记是否已点赞
local isLiked = redis.call('BF.EXISTS', key, noteId)
if isLiked == 1 then
    return 1
end

-- 未点赞则加入布隆过滤器
redis.call('BF.ADD', key, noteId)
return 0
