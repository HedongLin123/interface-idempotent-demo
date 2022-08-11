# 基于SpringBoot实现Api接口幂等性的几种方式

## 幂等性描述

幂等是数学里的一个概念，就是指同一个函数执行n次得到的结果是完全一致的。

API接口幂等指的的一个API结果无论执行N次，结果都是一样的。

* 1、查询类API接口，天然就是每次结果一样
* 2、一般的新增类API接口，如果数据库没有设置唯一键，天然不幂等，可能存在重复提交或者超时重试等请求，需要使用接口幂等性来实现
* 3、更新和删除，视情况而定


## 接口幂等性实现的几种方式

以新增数据接口幂等性分析

* 1、依靠数据库的唯一索引，重复则会报错
* 2、利用数据库的锁，对操作数据加锁
* 3、程序整体加锁，插入数据之前先查询数据是否存在，存在则不添加
* 4、使用token方式(前三种性能都比较低下，主要使用第4种)

## 使用token实现接口幂等性

实现思路

* 1、编写一个生成token的接口，调用业务接口前先调用生成token接口，拿到一个token
* 2、在请求头中携带token, 发起业务请求
* 3、业务请求接口收到请求，校验token，如果token存在，则删除token
* 4、如果token不存在，返回请先生成token或者请勿重复提交等提示信息
* 5、验证并删除token之后，执行业务逻辑(需要保证没有线程安全问题)
* 6、执行业务成功，则结束请求，执行业务失败，则将token重新放入redis中，以期待下次请求能够成功

重要问题

如何保证在多线程情况下，保证校验token，判断token是否存在，删除token，等操作不会出现线程安全问题，从而导致已经校验过的token，还未删除时又已经重新校验，并且校验成功了呢？

* 1、暴力解法，整个业务逻辑加分布式锁（想偷懒的方案）
* 2、针对校验token，判断token是否存在，删除token加锁，保证这部分的原子性（还不错的方案）
* 3、将校验token，判断token是否存在，删除token放在lua脚本中执行，lua脚本作为一个整体，天然是原子性操作（最终方案）


## 基础代码准备

### yml配置文件

```yaml
server:
  port: 8083

spring:
  redis:
    host: localhost
    port: 6379
    database: 0
    username:
    password:
    ssl: false
    jedis:
      pool:
        enabled: true
        min-idle: 0
        max-idle: 8
        max-wait: -1ms
        max-active: 8
    lettuce:
      pool:
        enabled: false
```

### 业务类型

生成token根据业务类型生成，方便管理

```java
/**
 * @Description 业务类型枚举值
 * @Author itdl
 * @Date 2022/08/11 10:15
 */
@Getter
public enum BusinessType implements BaseEnums<String, String>{
    CREATE_ORDER("create_order", "创建订单");

    /**键和值定义为code, value 实现BaseEnums+@Getter完成get方法*/
    private final String code;
    private final String value;

    BusinessType(String code, String value) {
        this.code = code;
        this.value = value;
    }

    /**
     * 校验编码code是否存在
     * @param code code
     */
    public static void checkCode(String code){
        for (BusinessType value : BusinessType.values()) {
            if (!value.code.equals(code)){
                throw new BizException(ResultCode.BUSINESS_TYPE_ERR);
            }
        }
    }
}
```

### token生成工具

```java
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
```

### token生成接口
```java
/**
 * @Description 接口幂等性controller
 * @Author itdl
 * @Date 2022/08/11 11:03
 */
@RestController
@RequestMapping("/idempotent")
@Slf4j
public class IdempotentController {
    @Autowired
    private TokenGenerator tokenGenerator;

    @GetMapping("/generatorToken")
    public TokenGenerator.TokenResult generatorToken(@RequestParam("businessType") String businessType) {
        final TokenGenerator.TokenResult tokenResult = tokenGenerator.generatorToken(businessType);
        log.info("=====================生成token成功：{}======================", tokenResult.getToken());
        return tokenResult;
    }
}    
```

### 并发调用接口测试幂等性

如果需要测试加锁的方式，只需要 synchronized (this){}部分代码打开，注释lua脚本方式即可。全局加锁就对整个方法加锁即可

```java
@GetMapping("/testInterface")
public String testInterface(@RequestParam(value = "token", defaultValue = "", required = false) String token) {
    if (!StringUtils.hasText(token)) {
        // 还可以从请求头获取
        throw new BizException(ResultCode.TOKEN_IS_NOT_EMPTY_ERR);
    }

    // 校验token
    try {
//            synchronized (this){
//                tokenGenerator.checkToken(token);
//                log.info("======================校验token成功：{}===================", token);
//                tokenGenerator.deleteToken(token);
//                log.info("============删除token：{}成功=============", token);
//            }
        tokenGenerator.checkAndDeleteToken(token);
        log.info("======================校验并删除token成功：{}===================", token);
    } catch (Exception e) {
        log.info("======================校验token失败：{}===================", token);
        return e.getMessage();
    }

    try {
        // 处理业务逻辑
        log.info("============处理业务逻辑开始=============");
        Thread.sleep(1000);
        log.info("============处理业务逻辑结束=============");
    } catch (InterruptedException e) {
        e.printStackTrace();
        tokenGenerator.resetToken(token);
    }

    return "success";
}

@GetMapping("/testInterface2")
public String testInterface2() {

    // 生成10个token， 多线程发起100次请求
    // 截取日志时减少该数量为4
    List<String> tokens = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
        final TokenGenerator.TokenResult token = this.generatorToken(BusinessType.CREATE_ORDER.getCode());
        tokens.add(token.getToken());
    }

    // 多线程发起100此请求，分十个任务
    // 截取日志时减少该数量为10
    List<Callable<Boolean>> tasks = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
        final Callable<Boolean> callable = () -> {
            final String success = testInterface(tokens.get(new Random().nextInt(tokens.size())));
            return "success".equals(success);
        };
        tasks.add(callable);
    }

    try {
        // 获取核心线程池大小，每次跑线程池个任务
        final int corePoolSize = scheduledThreadPoolExecutor.getCorePoolSize();
        final List<List<Callable<Boolean>>> lists = CollectionUtil.splitList(tasks, corePoolSize);
        for (List<Callable<Boolean>> list : lists) {
            // 循环跑，每次跑核心线程池个数个任务
            final List<Future<Boolean>> futures = scheduledThreadPoolExecutor.invokeAll(list);
            for (Future<Boolean> future : futures) {
                final Boolean result = future.get();
                if (!result){
//                        log.info("=========任务失败==========");
                }
            }
        }
    } catch (InterruptedException | ExecutionException e) {
        e.printStackTrace();
    }

    return "success";
}
```

### lua脚本内容

就是将获取token值，判断token值是否等于我们的默认值(等于就会存在，不等于就是不存在)，存在就删除token等操作封装为一个原子操作

```js
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
```

### 试试不加锁的情况会怎么样呢？

校验，判断，删除时不加锁，也不使用lua脚本

```shell
2022-08-11 14:36:21.111  INFO 13800 --- [nio-8083-exec-1] c.itdl.controller.IdempotentController   : =====================生成token成功：create_order:43897bcb3e2a460fa63c5c8c335ac3e6======================
2022-08-11 14:36:21.113  INFO 13800 --- [nio-8083-exec-1] c.itdl.controller.IdempotentController   : =====================生成token成功：create_order:d7c701c81edc413fa97316b47936b4ed======================
2022-08-11 14:36:21.114  INFO 13800 --- [nio-8083-exec-1] c.itdl.controller.IdempotentController   : =====================生成token成功：create_order:6dea06c160b7492bbc161d1d9a1f7973======================
2022-08-11 14:36:21.115  INFO 13800 --- [nio-8083-exec-1] c.itdl.controller.IdempotentController   : =====================生成token成功：create_order:642eaf85d4274c7baf58e1c46deacb69======================
2022-08-11 14:36:21.122  INFO 13800 --- [      统一可调度线程-1] c.itdl.controller.IdempotentController   : ======================校验token成功：create_order:642eaf85d4274c7baf58e1c46deacb69===================
2022-08-11 14:36:21.122  INFO 13800 --- [      统一可调度线程-4] c.itdl.controller.IdempotentController   : ======================校验token成功：create_order:642eaf85d4274c7baf58e1c46deacb69===================
2022-08-11 14:36:21.122  INFO 13800 --- [      统一可调度线程-2] c.itdl.controller.IdempotentController   : ======================校验token成功：create_order:43897bcb3e2a460fa63c5c8c335ac3e6===================
2022-08-11 14:36:21.123  INFO 13800 --- [      统一可调度线程-0] c.itdl.controller.IdempotentController   : ======================校验token成功：create_order:43897bcb3e2a460fa63c5c8c335ac3e6===================
2022-08-11 14:36:21.123  INFO 13800 --- [      统一可调度线程-7] c.itdl.controller.IdempotentController   : ======================校验token成功：create_order:43897bcb3e2a460fa63c5c8c335ac3e6===================
2022-08-11 14:36:21.123  INFO 13800 --- [      统一可调度线程-3] c.itdl.controller.IdempotentController   : ======================校验token成功：create_order:d7c701c81edc413fa97316b47936b4ed===================
2022-08-11 14:36:21.123  INFO 13800 --- [      统一可调度线程-5] c.itdl.controller.IdempotentController   : ======================校验token成功：create_order:642eaf85d4274c7baf58e1c46deacb69===================
2022-08-11 14:36:21.123  INFO 13800 --- [      统一可调度线程-6] c.itdl.controller.IdempotentController   : ======================校验token成功：create_order:d7c701c81edc413fa97316b47936b4ed===================
2022-08-11 14:36:22.131  INFO 13800 --- [      统一可调度线程-6] c.itdl.controller.IdempotentController   : ============删除token：create_order:d7c701c81edc413fa97316b47936b4ed成功=============
2022-08-11 14:36:22.131  INFO 13800 --- [      统一可调度线程-7] c.itdl.controller.IdempotentController   : ============删除token：create_order:43897bcb3e2a460fa63c5c8c335ac3e6成功=============
2022-08-11 14:36:22.132  INFO 13800 --- [      统一可调度线程-1] c.itdl.controller.IdempotentController   : ============删除token：create_order:642eaf85d4274c7baf58e1c46deacb69成功=============
2022-08-11 14:36:22.131  INFO 13800 --- [      统一可调度线程-3] c.itdl.controller.IdempotentController   : ============删除token：create_order:d7c701c81edc413fa97316b47936b4ed成功=============
2022-08-11 14:36:22.131  INFO 13800 --- [      统一可调度线程-2] c.itdl.controller.IdempotentController   : ============删除token：create_order:43897bcb3e2a460fa63c5c8c335ac3e6成功=============
2022-08-11 14:36:22.132  INFO 13800 --- [      统一可调度线程-5] c.itdl.controller.IdempotentController   : ============删除token：create_order:642eaf85d4274c7baf58e1c46deacb69成功=============
2022-08-11 14:36:22.132  INFO 13800 --- [      统一可调度线程-0] c.itdl.controller.IdempotentController   : ============删除token：create_order:43897bcb3e2a460fa63c5c8c335ac3e6成功=============
2022-08-11 14:36:22.132  INFO 13800 --- [      统一可调度线程-4] c.itdl.controller.IdempotentController   : ============删除token：create_order:642eaf85d4274c7baf58e1c46deacb69成功=============
2022-08-11 14:36:22.137  INFO 13800 --- [      统一可调度线程-7] c.itdl.controller.IdempotentController   : ======================校验token失败：create_order:d7c701c81edc413fa97316b47936b4ed===================
2022-08-11 14:36:22.137  INFO 13800 --- [      统一可调度线程-1] c.itdl.controller.IdempotentController   : ======================校验token失败：create_order:642eaf85d4274c7baf58e1c46deacb69===================
```

我明明只生成了4个token，为什么有8次校验成功了呢，这就是并发带来的问题，如下代码存在并发问题。

```java
@GetMapping("/testInterface")
public String testInterface(@RequestParam(value = "token", defaultValue = "", required = false) String token) {
    if (!StringUtils.hasText(token)) {
        // 还可以从请求头获取
        throw new BizException(ResultCode.TOKEN_IS_NOT_EMPTY_ERR);
    }

    // 校验token
    try {
        tokenGenerator.checkToken(token);
        log.info("======================校验token成功：{}===================", token);
        tokenGenerator.deleteToken(token);
        log.info("============删除token：{}成功=============", token);
    } catch (Exception e) {
        log.info("======================校验token失败：{}===================", token);
//            e.printStackTrace();
        return e.getMessage();
    }


    try {
        // 处理业务逻辑
//            log.info("============处理业务逻辑开始=============");
        Thread.sleep(1000);
//            log.info("============处理业务逻辑结束=============");
    } catch (InterruptedException e) {
        e.printStackTrace();
        tokenGenerator.resetToken(token);
    }

    return "success";
}
```

显然，我们的校验和删除token这两个操作不是原子操作，所以导致了并发问题，在这段代码之间创建一把锁试一下呢。

````java
synchronized (this){
    tokenGenerator.checkToken(token);
    log.info("======================校验token成功：{}===================", token);
    tokenGenerator.deleteToken(token);
    log.info("============删除token：{}成功=============", token);
}
````

正确结果展示, 这次是正确的生成生成了4个token，也只校验成功了4个token。
但是synchronized只能保证一个JVM的原子性，我们有多台机器就是无法保证的，所以需要加一把分布式锁。从而将查询比较删除操作实现原子性。

```html
2022-08-11 14:59:35.316  INFO 22584 --- [nio-8083-exec-1] c.itdl.controller.IdempotentController   : =====================生成token成功：create_order:3f4a678c05a64b4ba9e5a458e50715aa======================
2022-08-11 14:59:35.319  INFO 22584 --- [nio-8083-exec-1] c.itdl.controller.IdempotentController   : =====================生成token成功：create_order:327c9a0a176f4ee4ab5a73d5ad4fde42======================
2022-08-11 14:59:35.324  INFO 22584 --- [nio-8083-exec-1] c.itdl.controller.IdempotentController   : =====================生成token成功：create_order:5cb8876affdd4bdcbf30a3d21cc2df73======================
2022-08-11 14:59:35.326  INFO 22584 --- [nio-8083-exec-1] c.itdl.controller.IdempotentController   : =====================生成token成功：create_order:e7ec6723a3a14e558b19ff198e9473f8======================
2022-08-11 14:59:35.336  INFO 22584 --- [      统一可调度线程-0] c.itdl.controller.IdempotentController   : ======================校验token成功：create_order:327c9a0a176f4ee4ab5a73d5ad4fde42===================
2022-08-11 14:59:35.338  INFO 22584 --- [      统一可调度线程-0] c.itdl.controller.IdempotentController   : ============删除token：create_order:327c9a0a176f4ee4ab5a73d5ad4fde42成功=============
2022-08-11 14:59:35.339  INFO 22584 --- [      统一可调度线程-7] c.itdl.controller.IdempotentController   : ======================校验token成功：create_order:e7ec6723a3a14e558b19ff198e9473f8===================
2022-08-11 14:59:35.358  INFO 22584 --- [      统一可调度线程-7] c.itdl.controller.IdempotentController   : ============删除token：create_order:e7ec6723a3a14e558b19ff198e9473f8成功=============
2022-08-11 14:59:35.359  INFO 22584 --- [      统一可调度线程-6] c.itdl.controller.IdempotentController   : ======================校验token成功：create_order:5cb8876affdd4bdcbf30a3d21cc2df73===================
2022-08-11 14:59:35.361  INFO 22584 --- [      统一可调度线程-6] c.itdl.controller.IdempotentController   : ============删除token：create_order:5cb8876affdd4bdcbf30a3d21cc2df73成功=============
2022-08-11 14:59:35.364  INFO 22584 --- [      统一可调度线程-5] c.itdl.controller.IdempotentController   : ======================校验token失败：create_order:e7ec6723a3a14e558b19ff198e9473f8===================
2022-08-11 14:59:35.365  INFO 22584 --- [      统一可调度线程-4] c.itdl.controller.IdempotentController   : ======================校验token成功：create_order:3f4a678c05a64b4ba9e5a458e50715aa===================
2022-08-11 14:59:35.367  INFO 22584 --- [      统一可调度线程-4] c.itdl.controller.IdempotentController   : ============删除token：create_order:3f4a678c05a64b4ba9e5a458e50715aa成功=============
2022-08-11 14:59:35.368  INFO 22584 --- [      统一可调度线程-3] c.itdl.controller.IdempotentController   : ======================校验token失败：create_order:327c9a0a176f4ee4ab5a73d5ad4fde42===================
2022-08-11 14:59:35.368  INFO 22584 --- [      统一可调度线程-2] c.itdl.controller.IdempotentController   : ======================校验token失败：create_order:327c9a0a176f4ee4ab5a73d5ad4fde42===================
2022-08-11 14:59:35.369  INFO 22584 --- [      统一可调度线程-1] c.itdl.controller.IdempotentController   : ======================校验token失败：create_order:3f4a678c05a64b4ba9e5a458e50715aa===================
2022-08-11 14:59:36.384  INFO 22584 --- [      统一可调度线程-5] c.itdl.controller.IdempotentController   : ======================校验token失败：create_order:5cb8876affdd4bdcbf30a3d21cc2df73===================
2022-08-11 14:59:36.385  INFO 22584 --- [      统一可调度线程-3] c.itdl.controller.IdempotentController   : ======================校验token失败：create_order:327c9a0a176f4ee4ab5a73d5ad4fde42===================
```

那么除了分布式锁，还有其他的方法保证查询，比较，删除几个操作的原子性吗，答案是肯定的，通过lua脚本就可以，也是我们所推荐的方式。

```html
2022-08-11 16:01:33.877  INFO 15156 --- [nio-8083-exec-1] c.itdl.controller.IdempotentController   : =====================生成token成功：create_order:bc75be18e51a4ebdbe50c18802fa996c======================
2022-08-11 16:01:33.879  INFO 15156 --- [nio-8083-exec-1] c.itdl.controller.IdempotentController   : =====================生成token成功：create_order:68e528c56b64453097eccd0290a497ac======================
2022-08-11 16:01:33.880  INFO 15156 --- [nio-8083-exec-1] c.itdl.controller.IdempotentController   : =====================生成token成功：create_order:d1059951f7ae4cc18682205d33586052======================
2022-08-11 16:01:33.880  INFO 15156 --- [nio-8083-exec-1] c.itdl.controller.IdempotentController   : =====================生成token成功：create_order:3b3c684c813e4ee289f01445de4475af======================
2022-08-11 16:01:33.945  INFO 15156 --- [      统一可调度线程-1] c.itdl.controller.IdempotentController   : ======================校验并删除token成功：create_order:bc75be18e51a4ebdbe50c18802fa996c===================
2022-08-11 16:01:33.945  INFO 15156 --- [      统一可调度线程-6] c.itdl.controller.IdempotentController   : ======================校验并删除token成功：create_order:d1059951f7ae4cc18682205d33586052===================
2022-08-11 16:01:33.945  INFO 15156 --- [      统一可调度线程-7] c.itdl.controller.IdempotentController   : ======================校验并删除token成功：create_order:68e528c56b64453097eccd0290a497ac===================
2022-08-11 16:01:33.945  INFO 15156 --- [      统一可调度线程-1] c.itdl.controller.IdempotentController   : ============处理业务逻辑开始=============
2022-08-11 16:01:33.945  INFO 15156 --- [      统一可调度线程-6] c.itdl.controller.IdempotentController   : ============处理业务逻辑开始=============
2022-08-11 16:01:33.945  INFO 15156 --- [      统一可调度线程-2] c.itdl.controller.IdempotentController   : ======================校验并删除token成功：create_order:3b3c684c813e4ee289f01445de4475af===================
2022-08-11 16:01:33.945  INFO 15156 --- [      统一可调度线程-7] c.itdl.controller.IdempotentController   : ============处理业务逻辑开始=============
2022-08-11 16:01:33.945  INFO 15156 --- [      统一可调度线程-2] c.itdl.controller.IdempotentController   : ============处理业务逻辑开始=============
2022-08-11 16:01:33.947  INFO 15156 --- [      统一可调度线程-5] c.itdl.controller.IdempotentController   : ======================校验token失败：create_order:bc75be18e51a4ebdbe50c18802fa996c===================
2022-08-11 16:01:33.947  INFO 15156 --- [      统一可调度线程-3] c.itdl.controller.IdempotentController   : ======================校验token失败：create_order:bc75be18e51a4ebdbe50c18802fa996c===================
2022-08-11 16:01:33.947  INFO 15156 --- [      统一可调度线程-4] c.itdl.controller.IdempotentController   : ======================校验token失败：create_order:bc75be18e51a4ebdbe50c18802fa996c===================
2022-08-11 16:01:33.947  INFO 15156 --- [      统一可调度线程-0] c.itdl.controller.IdempotentController   : ======================校验token失败：create_order:d1059951f7ae4cc18682205d33586052===================
2022-08-11 16:01:34.952  INFO 15156 --- [      统一可调度线程-2] c.itdl.controller.IdempotentController   : ============处理业务逻辑结束=============
2022-08-11 16:01:34.952  INFO 15156 --- [      统一可调度线程-1] c.itdl.controller.IdempotentController   : ============处理业务逻辑结束=============
2022-08-11 16:01:34.952  INFO 15156 --- [      统一可调度线程-7] c.itdl.controller.IdempotentController   : ============处理业务逻辑结束=============
2022-08-11 16:01:34.952  INFO 15156 --- [      统一可调度线程-6] c.itdl.controller.IdempotentController   : ============处理业务逻辑结束=============
2022-08-11 16:01:34.955  INFO 15156 --- [      统一可调度线程-3] c.itdl.controller.IdempotentController   : ======================校验token失败：create_order:d1059951f7ae4cc18682205d33586052===================
2022-08-11 16:01:34.956  INFO 15156 --- [      统一可调度线程-5] c.itdl.controller.IdempotentController   : ======================校验token失败：create_order:d1059951f7ae4cc18682205d33586052===================
```

至此，完完全全的实现了使用token实现接口幂等性的操作，但是需要注意的是，业务逻辑处理本身也需要保证线程安全问题，最好在存在线程安全部分代码加上分布式锁。


## 项目地址

https://github.com/HedongLin123/interface-idempotent-demo




