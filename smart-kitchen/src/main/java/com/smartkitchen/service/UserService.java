package com.smartkitchen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartkitchen.dto.LoginDTO;
import com.smartkitchen.dto.LoginVO;
import com.smartkitchen.dto.WxLoginDTO;
import com.smartkitchen.dto.WxRegisterDTO;
import com.smartkitchen.entity.User;

public interface UserService extends IService<User> {
    LoginVO login(LoginDTO loginDTO);
    LoginVO wxLogin(WxLoginDTO wxLoginDTO);
    LoginVO wxRegister(WxRegisterDTO wxRegisterDTO);

    /**
     * 校验 token 有效性并返回用户信息
     * @param authHeader HTTP Authorization 请求头（Bearer xxx）
     * @return 用户登录信息（userId、role）
     */
    LoginVO checkToken(String authHeader);

    /**
     * 用 Refresh Token 换发新的 Access / Refresh Token（旧 Refresh 立即失效）。
     */
    LoginVO refresh(String refreshToken);

    /**
     * 登出：将当前 Access Token 拉黑，并删除 Refresh Token。
     * @param allDevices true 时吊销该用户全部设备
     */
    void logout(String authHeader, String refreshToken, boolean allDevices);
}