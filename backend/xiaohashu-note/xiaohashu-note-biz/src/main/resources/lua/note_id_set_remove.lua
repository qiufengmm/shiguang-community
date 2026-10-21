local setKey = KEYS[1]
local readyKey = KEYS[2]
local versionKey = KEYS[3]

if redis.call('EXISTS', readyKey) == 0 then
    return -1
end

local removed = redis.call('SREM', setKey, ARGV[1])
local ttlSeconds = tonumber(ARGV[2])
if removed == 1 then
    redis.call('INCR', versionKey)
    redis.call('EXPIRE', versionKey, ttlSeconds)
    if redis.call('EXISTS', setKey) == 1 then
        redis.call('EXPIRE', setKey, ttlSeconds)
    end
    redis.call('EXPIRE', readyKey, ttlSeconds)
end

return removed
