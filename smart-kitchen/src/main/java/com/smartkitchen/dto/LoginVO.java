package com.smartkitchen.dto;

import lombok.Data;

@Data
public class LoginVO {
    /** Access Token，请求业务接口时放在 Authorization Header */
    private String token;
    /** Refresh Token，仅用于 /api/auth/refresh 换发新 Access Token */
    private String refreshToken;
    private Long userId;
    private String role;
    private Boolean needRegister;
}