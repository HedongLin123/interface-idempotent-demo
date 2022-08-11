-- 保证接口幂等性的lua脚本

-- 获取第一个参数 表示token
local key = KEYS[1]
-- 获取第二个参数 表示token的值，默认为HI，这里不在里面写死，防止其他地方也会使用
local value = KEYS[2]

-- 比较值是否相等
if redis.call('get', key)  == value then
    -- 删除redis的key, 返回校验成功标识
    redis.call('del', key)
    return 1
else
    return 0
end

