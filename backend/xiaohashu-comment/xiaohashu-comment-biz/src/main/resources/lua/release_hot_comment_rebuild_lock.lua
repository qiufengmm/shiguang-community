-- KEYS[1]: per-note rebuild lock
-- ARGV[1]: owner token
if redis.call("GET", KEYS[1]) == ARGV[1] then
    return redis.call("DEL", KEYS[1])
end
return 0
