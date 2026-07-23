for i = 1, #KEYS do
    local stock = tonumber(redis.call('get', KEYS[i]))
    local num = tonumber(ARGV[i])
    if not stock or stock < num then
        return -i
    end
end
for i = 1, #KEYS do
    local num = tonumber(ARGV[i])
    redis.call('decrby', KEYS[i], num)
end
return 1