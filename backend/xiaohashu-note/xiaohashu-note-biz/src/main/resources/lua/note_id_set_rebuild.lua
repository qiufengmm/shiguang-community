local setKey = KEYS[1]
local readyKey = KEYS[2]
local versionKey = KEYS[3]

local currentVersion = redis.call('GET', versionKey) or '0'
if currentVersion ~= ARGV[1] then
    return 0
end

local ttlSeconds = tonumber(ARGV[2])
redis.call('DEL', setKey)
for i = 3, #ARGV do
    redis.call('SADD', setKey, ARGV[i])
end

if #ARGV > 2 then
    redis.call('EXPIRE', setKey, ttlSeconds)
end
redis.call('SET', readyKey, '1', 'EX', ttlSeconds)
redis.call('INCR', versionKey)
redis.call('EXPIRE', versionKey, ttlSeconds)
return 1
