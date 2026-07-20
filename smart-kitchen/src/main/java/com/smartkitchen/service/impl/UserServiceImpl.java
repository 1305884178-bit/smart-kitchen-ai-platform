package com.smartkitchen.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartkitchen.config.JwtUtil;
import com.smartkitchen.dto.LoginDTO;
import com.smartkitchen.dto.LoginVO;
import com.smartkitchen.dto.WxLoginDTO;
import com.smartkitchen.dto.WxRegisterDTO;
import com.smartkitchen.entity.User;
import com.smartkitchen.mapper.UserMapper;
import com.smartkitchen.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    public LoginVO login(LoginDTO loginDTO) {
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("username", loginDTO.getUsername());
        User user = this.getOne(queryWrapper);

        if (user == null || !user.getPassword().equals(loginDTO.getPassword())) {
            throw new RuntimeException("账号或密码错误");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getRole(), user.getOpenid());
        
        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setUserId(user.getId());
        vo.setRole(user.getRole());
        vo.setNeedRegister(false);
        return vo;
    }

    @Override
    public LoginVO wxLogin(WxLoginDTO wxLoginDTO) {
        // 1. 调用微信 API 获取 openid (此处使用 code 模拟 openid 用于测试)
        // 生产环境应调用: https://api.weixin.qq.com/sns/jscode2session
        String openid = "mock_openid_" + wxLoginDTO.getCode();

        // 2. 查库看是否是老用户
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("openid", openid);
        User user = this.getOne(queryWrapper);

        LoginVO vo = new LoginVO();
        if (user != null) {
            // 老用户，直接发 token
            String token = jwtUtil.generateToken(user.getId(), user.getRole(), user.getOpenid());
            vo.setToken(token);
            vo.setUserId(user.getId());
            vo.setRole(user.getRole());
            vo.setNeedRegister(false);
        } else {
            // 新用户，通知前端需要注册
            vo.setNeedRegister(true);
        }
        return vo;
    }

    @Override
    public LoginVO wxRegister(WxRegisterDTO wxRegisterDTO) {
        // 再次获取 openid
        String openid = "mock_openid_" + wxRegisterDTO.getCode();

        // 检查是否已注册（防并发）
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("openid", openid);
        if (this.count(queryWrapper) > 0) {
            throw new RuntimeException("该微信已注册");
        }

        User user = new User();
        user.setOpenid(openid);
        user.setPhone(wxRegisterDTO.getPhone());
        user.setNickname(wxRegisterDTO.getNickname());
        user.setAvatar(wxRegisterDTO.getAvatar());
        user.setRole("CUSTOMER");
        user.setCreateTime(LocalDateTime.now());
        this.save(user);

        String token = jwtUtil.generateToken(user.getId(), user.getRole(), user.getOpenid());
        
        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setUserId(user.getId());
        vo.setRole(user.getRole());
        vo.setNeedRegister(false);
        return vo;
    }
}