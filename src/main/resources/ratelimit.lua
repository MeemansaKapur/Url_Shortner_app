-- Token bucket, executed atomically by Redis.
-- KEYS[1] = "rl:<apiKeyId>"
-- ARGV[1] = capacity (max burst), ARGV[2] = refill tokens per second
-- Returns { allowed (0|1), remaining tokens (floored), retry-after in ms (0 if allowed) }
local capacity = tonumber(ARGV[1])
local rate     = tonumber(ARGV[2])

-- Redis server clock, so every app instance refills against the same clock
local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local state  = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1]) or capacity
local ts     = tonumber(state[2]) or now

tokens = math.min(capacity, tokens + math.max(0, now - ts) / 1000 * rate)

local allowed = 0
local retry_ms = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retry_ms = math.ceil((1 - tokens) / rate * 1000)
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
-- Idle buckets disappear on their own (no memory leak from one-off keys)
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate * 1000) * 2)

return { allowed, math.floor(tokens), retry_ms }
