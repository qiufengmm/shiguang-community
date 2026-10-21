local key = KEYS[1]

for i = 1, #ARGV - 1 do
    redis.call('R.SETBIT', key, ARGV[i], 1)
end

local expireTime = ARGV[#ARGV]
redis.call('EXPIRE', key, expireTime)
return 0
