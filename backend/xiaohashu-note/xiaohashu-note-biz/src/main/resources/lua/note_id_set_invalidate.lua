local setKey = KEYS[1]
local readyKey = KEYS[2]
local versionKey = KEYS[3]
local ttlSeconds = tonumber(ARGV[1])

redis.call('DEL', setKey, readyKey)
redis.call('INCR', versionKey)
redis.call('EXPIRE', versionKey, ttlSeconds)
return 1
