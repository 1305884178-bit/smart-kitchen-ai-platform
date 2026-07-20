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
}