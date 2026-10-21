-- KEYS[1]: hot-comment ZSET
-- KEYS[2]: generation key
-- KEYS[3]: ready marker
-- ARGV[1]: zero-based offset
-- ARGV[2]: page size
local currentGeneration = redis.call("GET", KEYS[2]) or "0"
if redis.call("GET", KEYS[3]) ~= currentGeneration then
    return nil
end

local offset = tonumber(ARGV[1])
local pageSize = tonumber(ARGV[2])
if not offset or offset < 0 or offset ~= math.floor(offset)
        or not pageSize or pageSize < 1 or pageSize > 10 or pageSize ~= math.floor(pageSize) then
    return redis.error_reply("invalid hot-comment page range")
end

return redis.call("ZREVRANGE", KEYS[1], offset, offset + pageSize - 1)
