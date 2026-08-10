package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import com.smartkitchen.dto.LoginDTO;
import com.smartkitchen.dto.LoginVO;
import com.smartkitchen.dto.WxLoginDTO;
import com.smartkitchen.dto.WxRegisterDTO;
import com.smartkitchen.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证授权控制器
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserService userService;

    /**
     * 微信免密登录
     * @param wxLoginDTO 包含 wx.login 获取的 code
     * @return 登录结果
     */
    @PostMapping("/wx-login")
    public Result<LoginVO> wxLogin(@RequestBody WxLoginDTO wxLoginDTO) {
        return Result.success(userService.wxLogin(wxLoginDTO));
    }

    /**
     * 微信新用户注册绑定
     * @param wxRegisterDTO 包含 code 及用户填写的资料
     * @return 登录结果
     */
    @PostMapping("/register")
    public Result<LoginVO> register(@RequestBody WxRegisterDTO wxRegisterDTO) {
        return Result.success(userService.wxRegister(wxRegisterDTO));
    }

    /**
     * 账号密码登录（供 B 端管理或 PC 测试使用）
     * @param loginDTO 用户名密码
     * @return 登录结果
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody LoginDTO loginDTO) {
        return Result.success(userService.login(loginDTO));
    }

    /**
     * 校验 token 有效性
     * @param request HTTP 请求（用于提取 Authorization header）
     * @return 用户信息
     */
    @GetMapping("/check-token")
    public Result<LoginVO> checkToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        return Result.success(userService.checkToken(authHeader));
    }
}
