-- 1.参数列表
-- 1.1优惠卷id
local voucherId = ARGV[1]
--  1.2用户id
local userId = ARGV[2]

-- 2.数据key
-- 2.1 库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2 订单key
local orderKey = 'seckill:order:' .. userId

-- 3. 脚本业务
-- 3.1 判断库存是否充足
--     注意：key 不存在时 redis.call('get', ...) 返回的是 false，
--     tonumber(false) 得到 nil，而 nil 和数字比较会让整个脚本报错
--     （ERR user_script:15: attempt to compare nil with number）。
--     所以必须先判 nil，再比较。
local stock = tonumber(redis.call('get', stockKey))
if(stock == nil or stock <= 0) then
    -- 3.2 库存不足（或库存 key 还没预热）返回1
    return 1
end
-- 3.2 判读用户是否下单
if(redis.call('sismember' ,orderKey ,userId) == 1)then
    --3.3存在说明重复存在返回2
    return 2
end
-- 3.4扣库存 incrby stockey -1
redis.call('incrby', stockKey, -1)
-- 3.5 下单(保存用户) sadd orderKey userId
redis.call('sadd', orderKey, userId)

-- 3.6 抢购成功，必须显式 return 0
--     Lua 脚本"执行完没有 return"时，Redis 回给客户端的是 nil，
--     Java 侧 stringRedisTemplate.execute(...) 就会得到 null，
--     下一行 result.intValue() 立刻抛 NullPointerException。
return 0
