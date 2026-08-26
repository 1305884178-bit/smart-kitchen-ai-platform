-- ARGV 约定（n = #KEYS，与 KEYS 按下标一一对应）：
--   ARGV[1..n]     每道菜扣减数量
--   ARGV[n+1..2n]  每道菜初始库存；仅当对应 Redis key 不存在时写入再扣
-- 语义：全菜品都够才扣；有一个不够则全部不扣（本脚本在 Redis 内原子执行）。
local n = #KEYS
for i = 1, n do
    local stock = tonumber(redis.call('get', KEYS[i]))
    if not stock then
        stock = tonumber(ARGV[n + i])
    end
    local num = tonumber(ARGV[i])
    if not stock or not num or stock < num then
        return -i
    end
end
for i = 1, n do
    local num = tonumber(ARGV[i])
    if redis.call('exists', KEYS[i]) == 0 then
        redis.call('set', KEYS[i], ARGV[n + i])
    end
    redis.call('decrby', KEYS[i], num)
end
return 1
