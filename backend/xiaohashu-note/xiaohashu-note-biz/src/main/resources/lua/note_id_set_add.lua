local setKey = KEYS[1]
local readyKey = KEYS[2]
local versionKey = KEYS[3]

if redis.call('EXISTS', readyKey) == 0 then
    return -1
end

local added = redis.call('SADD', setKey, ARGV[1])
local ttlSeconds = tonumber(ARGV[2])
if added == 1 then
    redis.call('INCR', versionKey)
    redis.call('EXPIRE', versionKey, ttlSeconds)
    redis.call('EXPIRE', setKey, ttlSeconds)
    redis.call('EXPIRE', readyKey, ttlSeconds)
end

return added
