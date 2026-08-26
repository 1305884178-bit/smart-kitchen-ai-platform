-- ARGV 约定（n = #KEYS，与 KEYS 按下标一一对应）：
--   ARGV[1..n]     返还数量
--   ARGV[n+1..2n]  返还后的 MySQL daily_stock；仅当 Redis key 不存在时写入
-- 语义：key 存在则 INCRBY 返还数量；key 不存在则 SET 为 DB 当前库存，避免从 0 起跳。
local n = #KEYS
for i = 1, n do
    local num = tonumber(ARGV[i])
    if redis.call('exists', KEYS[i]) == 1 then
        redis.call('incrby', KEYS[i], num)
    else
        redis.call('set', KEYS[i], ARGV[n + i])
    end
end
return 1
