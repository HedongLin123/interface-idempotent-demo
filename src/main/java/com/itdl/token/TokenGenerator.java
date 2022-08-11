package com.itdl.token;

import com.itdl.common.base.ResultCode;
import com.itdl.common.constant.Constants;
import com.itdl.common.enums.BusinessType;
import com.itdl.common.exception.BizException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @Description Token生成器
 * @Author itdl
 * @Date 2022/08/11 10:09
 */
@Component
@Slf4j
public class TokenGenerator {
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    /**
     * token的有效时间 单位分钟
     */
    private static final Integer TTL_TIME = 5;

    private static final String DEFAULT_TOKEN_VALUE = "HI";

    /**
     * 根据业务生成token
     * @param businessType 业务类型
     * @return
     */
    public TokenResult generatorToken(String businessType){
        // 校验业务类型
        BusinessType.checkCode(businessType);

        // 根据业务类型作为前缀生成token
        String key = businessType + ":" + UUID.randomUUID().toString().replaceAll("-", "");

        // 存入缓存
        redisTemplate.opsForValue().set(Constants.REDIS_PREDIX + key, DEFAULT_TOKEN_VALUE, TTL_TIME, TimeUnit.MINUTES);
        return new TokenResult(key);
    }


    /**
     * 校验Token是否过期或者被删除
     * 校验重复思路：先调用生成token接口， 携带token发起请求
     * 1、判断token是否存在，不存在则直接返回错误（请先生成token或token已失效）
     * 2、token存在，则继续处理业务，业务处理完毕之后删除redis中的keu
     *
     * @param token token
     */
    public void checkToken(String token){
        // 此处应该保持原子性，查询和判断在一起并不能保证原子性
        // 如何保证原子性  1、加分布式锁  2、使用lua脚本将两个操作封装在一起
        final String value = redisTemplate.opsForValue().get(Constants.REDIS_PREDIX + token);
        if (!StringUtils.hasText(value)){
            throw new BizException(ResultCode.INTERFACE_REPEAT_COMMIT_ERR);
        }
    }


    /**
     * 删除token
     * @param token token
     */
    public void deleteToken(String token){
        redisTemplate.delete(Constants.REDIS_PREDIX + token);
    }


    /**
     * 校验并立马删除token
     * @param token token
     */
    public void checkAndDeleteToken(String token){
        // lua脚本的名称，在resource下面 idempotent.lua
        final DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("idempotent.lua"));
        redisScript.setResultType(Long.class);
        Long result;
        try {
            // 这里传入lua脚本需要的keys
            result = redisTemplate.execute(redisScript, Arrays.asList(Constants.REDIS_PREDIX + token, DEFAULT_TOKEN_VALUE));
        } catch (Exception e) {
            e.printStackTrace();
            throw new BizException(ResultCode.LUA_SCRIPT_EXEC_ERR);
        }
        // 返回0表示结果为空
        if (result == null || result == 0){
            throw new BizException(ResultCode.INTERFACE_REPEAT_COMMIT_ERR);
        }
    }

    /**
     * 重新存入缓存
     * @param token token
     */
    public void resetToken(String token) {
        // 存入缓存
        redisTemplate.opsForValue().set(Constants.REDIS_PREDIX + token, DEFAULT_TOKEN_VALUE, TTL_TIME, TimeUnit.MINUTES);
    }


    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenResult implements Serializable {
        private String token;
    }
}
