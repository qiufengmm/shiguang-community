local key = KEYS[1]
local zaddArgs = {}

for i = 1, #ARGV - 1, 2 do
    table.insert(zaddArgs, ARGV[i])
    table.insert(zaddArgs, ARGV[i + 1])
end

redis.call('ZADD', key, unpack(zaddArgs))
local expireTime = ARGV[#ARGV]
redis.call('EXPIRE', key, expireTime)
return 0
