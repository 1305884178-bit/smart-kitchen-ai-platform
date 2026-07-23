for i = 1, #KEYS do
    local num = tonumber(ARGV[i])
    redis.call('incrby', KEYS[i], num)
end
return 1