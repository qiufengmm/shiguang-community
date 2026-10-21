-- LUA script: check whether a note is collected in the user's Roaring Bitmap

local key = KEYS[1]
local noteId = ARGV[1]

local exists = redis.call('EXISTS', key)
if exists == 0 then
    return -1
end

return redis.call('R.GETBIT', key, noteId)
