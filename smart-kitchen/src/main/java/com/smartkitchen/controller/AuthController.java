package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证授权控制器
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * 用户登录接口
     * @return 返回包含JWT Token的登录结果
     */
    @PostMapping("/login")
    public Result<String> login() {
        return Result.success(null);
    }
}
