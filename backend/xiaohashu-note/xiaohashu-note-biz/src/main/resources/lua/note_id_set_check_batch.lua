local setKey = KEYS[1]
local readyKey = KEYS[2]
local results = {}

if redis.call('EXISTS', readyKey) == 0 then
    results[1] = -1
    return results
end

for i = 1, #ARGV do
    results[i] = redis.call('SISMEMBER', setKey, ARGV[i])
end

return results
