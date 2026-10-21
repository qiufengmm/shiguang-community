-- KEYS[1]: hot-comment ZSET
-- KEYS[2]: per-note generation key (kept without TTL to protect in-flight rebuilds)
-- KEYS[3]: ready marker containing the generation represented by the ZSET
-- ARGV: INVALIDATE, or UPDATE, ttlSeconds, member1, score1, ...
local zsetKey = KEYS[1]
local generationKey = KEYS[2]
local readyKey = KEYS[3]
local action = ARGV[1]

if action == "INVALIDATE" then
    if #ARGV ~= 1 then
        return redis.error_reply("INVALIDATE does not accept extra arguments")
    end
    local generation = redis.call("INCR", generationKey)
    redis.call("DEL", zsetKey, readyKey)
    return generation
end

if action ~= "UPDATE" then
    return redis.error_reply("unknown hot-comment action")
end

if #ARGV < 4 or ((#ARGV - 2) % 2) ~= 0 then
    return redis.error_reply("hot-comment updates require member/score pairs")
end

local ttlSeconds = tonumber(ARGV[2])
if not ttlSeconds or ttlSeconds < 1 or ttlSeconds > 18000 or ttlSeconds ~= math.floor(ttlSeconds) then
    return redis.error_reply("invalid cache ttl")
end

local updates = {}
for i = 3, #ARGV, 2 do
    local member = ARGV[i]
    local score = tonumber(ARGV[i + 1])
    if not member or string.len(member) ~= 19 or not string.match(member, "^%d+$") then
        return redis.error_reply("invalid hot-comment member")
    end
    if not score or score ~= score or score <= -math.huge or score >= math.huge then
        return redis.error_reply("invalid score")
    end
    updates[#updates + 1] = { member, score }
end

local oldGeneration = redis.call("GET", generationKey) or "0"
local nextGeneration = redis.call("INCR", generationKey)
if redis.call("GET", readyKey) ~= oldGeneration then
    -- A missing/mismatched ready marker means the current ZSET is legacy or partial.
    redis.call("DEL", zsetKey, readyKey)
    return nextGeneration
end

for _, update in ipairs(updates) do
    local member = update[1]
    local score = update[2]
    local currentScore = redis.call("ZSCORE", zsetKey, member)

    if currentScore then
        redis.call("ZADD", zsetKey, score, member)
    else
        local currentSize = redis.call("ZCARD", zsetKey)
        if currentSize < 500 then
            redis.call("ZADD", zsetKey, score, member)
        else
            local minimum = redis.call("ZRANGE", zsetKey, 0, 0, "WITHSCORES")
            local minimumScore = tonumber(minimum[2])
            if score > minimumScore or (score == minimumScore and member > minimum[1]) then
                redis.call("ZADD", zsetKey, score, member)
            end
        end
    end
end

while redis.call("ZCARD", zsetKey) > 500 do
    redis.call("ZREMRANGEBYRANK", zsetKey, 0, 0)
end

local finalSize = redis.call("ZCARD", zsetKey)
if finalSize > 0 then
    redis.call("EXPIRE", zsetKey, ttlSeconds)
else
    redis.call("DEL", zsetKey)
end
redis.call("SET", readyKey, nextGeneration, "EX", ttlSeconds)
return nextGeneration
