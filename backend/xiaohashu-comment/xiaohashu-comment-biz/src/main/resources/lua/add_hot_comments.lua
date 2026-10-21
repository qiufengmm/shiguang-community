-- 操作的 Key
local zsetKey = KEYS[1]
-- 获取传入的成员和分数列表
local membersScores = ARGV
-- ZSet 最多缓存 500 条评论
local sizeLimit = 500

-- 检查 ZSet 是否存在
if redis.call('EXISTS', zsetKey) == 0 then
    return -1
end

-- 获取当前 ZSet 的大小
local currentSize = redis.call('ZCARD', zsetKey)

-- 遍历传入的成员和分数，添加到 ZSet 中
for i = 1, #membersScores, 2 do
    local member = membersScores[i]
    local score = membersScores[i + 1]

    if currentSize < sizeLimit then
        redis.call('ZADD', zsetKey, score, member)
        currentSize = currentSize + 1
    else
        break
    end
end

return 0
