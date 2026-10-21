local key = KEYS[1]
local noteId = ARGV[1]

local exists = redis.call('EXISTS', key)
if exists == 0 then
    return -1
end

local isCollected = redis.call('R.GETBIT', key, noteId)
if isCollected == 1 then
    return 1
end

redis.call('R.SETBIT', key, noteId, 1)
return 0
