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
}