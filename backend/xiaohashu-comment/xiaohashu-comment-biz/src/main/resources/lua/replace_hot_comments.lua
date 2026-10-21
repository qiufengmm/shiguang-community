-- KEYS[1]: target ZSET
-- KEYS[2]: generation key
-- KEYS[3]: ready marker
-- KEYS[4]: temporary ZSET (may be absent for an empty SQL snapshot)
-- KEYS[5]: per-note rebuild lock
-- ARGV[1]: generation observed before the SQL snapshot
-- ARGV[2]: cache TTL seconds
-- ARGV[3]: owner token of the rebuild lock
local targetKey = KEYS[1]
local generationKey = KEYS[2]
local readyKey = KEYS[3]
local temporaryKey = KEYS[4]
local lockKey = KEYS[5]
local expectedGeneration = ARGV[1]
local ttlSeconds = tonumber(ARGV[2])
local lockToken = ARGV[3]
local currentGeneration = redis.call("GET", generationKey) or "0"

if not ttlSeconds or ttlSeconds < 1 or ttlSeconds > 18000 or ttlSeconds ~= math.floor(ttlSeconds) then
    return redis.error_reply("invalid cache ttl")
end

if redis.call("GET", lockKey) ~= lockToken then
    redis.call("DEL", temporaryKey)
    return -1
end

if currentGeneration ~= expectedGeneration then
    redis.call("DEL", temporaryKey)
    return -1
end

local snapshotSize = redis.call("ZCARD", temporaryKey)
if snapshotSize > 500 then
    redis.call("DEL", temporaryKey)
    return redis.error_reply("hot-comment snapshot exceeds 500 rows")
end

redis.call("DEL", targetKey)
if snapshotSize > 0 then
    redis.call("RENAME", temporaryKey, targetKey)
    redis.call("EXPIRE", targetKey, ttlSeconds)
else
    redis.call("DEL", temporaryKey)
end

redis.call("SET", readyKey, expectedGeneration, "EX", ttlSeconds)
return snapshotSize
