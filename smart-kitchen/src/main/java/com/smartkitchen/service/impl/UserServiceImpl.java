package com.smartkitchen.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartkitchen.config.JwtUtil;
import com.smartkitchen.dto.LoginDTO;
import com.smartkitchen.dto.LoginVO;
import com.smartkitchen.dto.WxLoginDTO;
import com.smartkitchen.dto.WxRegisterDTO;
import com.smartkitchen.entity.User;
import com.smartkitchen.mapper.UserMapper;
import com.smartkitchen.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${smart-kitchen.wechat.app-id}")
    private String wechatAppId;

    @Value("${smart-kitchen.wechat.app-secret}")
    private String wechatAppSecret;

    /** 微信 code2session 接口地址 */
    private static final String WX_CODE2SESSION_URL =
            "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code";

    /**
     * 通过微信 code 换取 openid
     * @param code 小程序端 wx.login() 获取的临时登录凭证
     * @return openid，失败时抛异常
     */
    private String getOpenidFromWechat(String code) {
        String url = String.format(WX_CODE2SESSION_URL, wechatAppId, wechatAppSecret, code);
        String response = restTemplate.getForObject(url, String.class);
        try {
            JsonNode json = new ObjectMapper().readTree(response);
            if (json.has("errcode") && json.get("errcode").asInt() != 0) {
                throw new RuntimeException("微信登录失败：" + json.get("errmsg").asText());
            }
            return json.get("openid").asText();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("微信接口响应解析失败", e);
        }
    }

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
        // 1. 调用微信 API 用 code 换取 openid
        String openid = getOpenidFromWechat(wxLoginDTO.getCode());

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
        // 再次用 code 换取 openid
        String openid = getOpenidFromWechat(wxRegisterDTO.getCode());

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

    @Override
    public LoginVO checkToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("未提供有效的认证信息");
        }

        String token = authHeader.substring(7);

        if (!jwtUtil.validateToken(token)) {
            throw new RuntimeException("Token无效或已过期");
        }

        io.jsonwebtoken.Claims claims = jwtUtil.parseToken(token);
        Long userId = claims.get("userId", Long.class);
        String role = claims.get("role", String.class);

        if (userId == null) {
            throw new RuntimeException("Token中缺少用户标识");
        }

        User user = this.getById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setUserId(user.getId());
        vo.setRole(user.getRole());
        vo.setNeedRegister(false);
        return vo;
    }
}