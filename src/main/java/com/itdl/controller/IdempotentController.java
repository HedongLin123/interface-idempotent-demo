package com.itdl.controller;

import com.baomidou.mybatisplus.extension.api.R;
import com.itdl.common.base.ResultCode;
import com.itdl.common.enums.BusinessType;
import com.itdl.common.exception.BizException;
import com.itdl.common.util.CollectionUtil;
import com.itdl.token.TokenGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;

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

    @Resource
    private ScheduledThreadPoolExecutor scheduledThreadPoolExecutor;

    @GetMapping("/generatorToken")
    public TokenGenerator.TokenResult generatorToken(@RequestParam("businessType") String businessType) {
        final TokenGenerator.TokenResult tokenResult = tokenGenerator.generatorToken(businessType);
        log.info("=====================生成token成功：{}======================", tokenResult.getToken());
        return tokenResult;
    }


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





}
