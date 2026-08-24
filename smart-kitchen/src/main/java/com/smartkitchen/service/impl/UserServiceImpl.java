package com.smartkitchen.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartkitchen.config.JwtUtil;
import com.smartkitchen.config.TokenStore;
import com.smartkitchen.dto.LoginDTO;
import com.smartkitchen.dto.LoginVO;
import com.smartkitchen.dto.WxLoginDTO;
import com.smartkitchen.dto.WxRegisterDTO;
import com.smartkitchen.entity.User;
import com.smartkitchen.mapper.UserMapper;
import com.smartkitchen.service.UserService;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenStore tokenStore;

    @Autowired
    private PasswordEncoder passwordEncoder;

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

        if (user == null || !matchesPassword(loginDTO.getPassword(), user)) {
            throw new RuntimeException("账号或密码错误");
        }

        return issueTokens(user);
    }

    /**
     * BCrypt 校验；兼容历史明文密码，校验通过后升级为哈希。
     */
    private boolean matchesPassword(String rawPassword, User user) {
        String stored = user.getPassword();
        if (stored == null || rawPassword == null) {
            return false;
        }
        if (isBcryptHash(stored)) {
            return passwordEncoder.matches(rawPassword, stored);
        }
        if (stored.equals(rawPassword)) {
            user.setPassword(passwordEncoder.encode(rawPassword));
            this.updateById(user);
            return true;
        }
        return false;
    }

    private boolean isBcryptHash(String stored) {
        return stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$");
    }

    @Override
    public LoginVO wxLogin(WxLoginDTO wxLoginDTO) {
        String openid = getOpenidFromWechat(wxLoginDTO.getCode());

        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("openid", openid);
        User user = this.getOne(queryWrapper);

        LoginVO vo = new LoginVO();
        if (user != null) {
            return issueTokens(user);
        }
        vo.setNeedRegister(true);
        return vo;
    }

    @Override
    public LoginVO wxRegister(WxRegisterDTO wxRegisterDTO) {
        String openid = getOpenidFromWechat(wxRegisterDTO.getCode());

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

        return issueTokens(user);
    }

    @Override
    public LoginVO checkToken(String authHeader) {
        Claims claims = parseAccessToken(authHeader);
        Long userId = claims.get("userId", Long.class);
        if (userId == null) {
            throw new RuntimeException("Token中缺少用户标识");
        }
        User user = this.getById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        LoginVO vo = new LoginVO();
        vo.setToken(extractBearer(authHeader));
        vo.setUserId(user.getId());
        vo.setRole(user.getRole());
        vo.setNeedRegister(false);
        return vo;
    }

    @Override
    public LoginVO refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new RuntimeException("缺少 Refresh Token");
        }
        if (!jwtUtil.validateToken(refreshToken)) {
            throw new RuntimeException("Refresh Token无效或已过期");
        }
        Claims claims = jwtUtil.parseToken(refreshToken);
        if (!jwtUtil.isRefreshToken(claims)) {
            throw new RuntimeException("请使用 Refresh Token");
        }
        Long userId = claims.get("userId", Long.class);
        String jti = claims.getId();
        if (userId == null || jti == null || !tokenStore.isRefreshValid(jti, userId)) {
            throw new RuntimeException("Refresh Token无效或已过期");
        }
        User user = this.getById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        tokenStore.removeRefreshToken(jti, userId);
        return issueTokens(user);
    }

    @Override
    public void logout(String authHeader, String refreshToken, boolean allDevices) {
        Long userId = null;
        try {
            Claims accessClaims = parseAccessToken(authHeader);
            userId = accessClaims.get("userId", Long.class);
            tokenStore.blacklist(accessClaims.getId(), jwtUtil.remainingTtlMillis(accessClaims));
        } catch (RuntimeException ignored) {
            // Access 已失效时仍尝试作废 Refresh
        }

        if (refreshToken != null && !refreshToken.isBlank() && jwtUtil.validateToken(refreshToken)) {
            Claims refreshClaims = jwtUtil.parseToken(refreshToken);
            if (jwtUtil.isRefreshToken(refreshClaims)) {
                Long refreshUserId = refreshClaims.get("userId", Long.class);
                if (userId == null) {
                    userId = refreshUserId;
                }
                if (refreshUserId != null) {
                    tokenStore.removeRefreshToken(refreshClaims.getId(), refreshUserId);
                }
            }
        }

        if (allDevices && userId != null) {
            tokenStore.revokeUser(userId, jwtUtil.getAccessExpiration());
        }
    }

    private LoginVO issueTokens(User user) {
        String access = jwtUtil.generateAccessToken(user.getId(), user.getRole(), user.getOpenid());
        String refresh = jwtUtil.generateRefreshToken(user.getId(), user.getRole());
        Claims refreshClaims = jwtUtil.parseToken(refresh);
        tokenStore.saveRefreshToken(refreshClaims.getId(), user.getId(), jwtUtil.getRefreshExpiration());

        LoginVO vo = new LoginVO();
        vo.setToken(access);
        vo.setRefreshToken(refresh);
        vo.setUserId(user.getId());
        vo.setRole(user.getRole());
        vo.setNeedRegister(false);
        return vo;
    }

    private Claims parseAccessToken(String authHeader) {
        String token = extractBearer(authHeader);
        if (!jwtUtil.validateToken(token)) {
            throw new RuntimeException("Token无效或已过期");
        }
        Claims claims = jwtUtil.parseToken(token);
        if (!jwtUtil.isAccessToken(claims) || tokenStore.isBlacklisted(claims.getId())) {
            throw new RuntimeException("Token无效或已过期");
        }
        Long userId = claims.get("userId", Long.class);
        if (userId != null && tokenStore.isUserRevoked(userId, jwtUtil.issuedAtMillis(claims))) {
            throw new RuntimeException("Token无效或已过期");
        }
        return claims;
    }

    private String extractBearer(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("未提供有效的认证信息");
        }
        return authHeader.substring(7);
    }
}
