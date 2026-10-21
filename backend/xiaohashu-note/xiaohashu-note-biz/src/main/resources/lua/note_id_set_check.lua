local setKey = KEYS[1]
local readyKey = KEYS[2]

if redis.call('EXISTS', readyKey) == 0 then
    return -1
end

return redis.call('SISMEMBER', setKey, ARGV[1])
