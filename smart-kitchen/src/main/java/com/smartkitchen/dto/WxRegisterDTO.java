package com.smartkitchen.dto;

import lombok.Data;

@Data
public class WxRegisterDTO {
    private String code;
    private String nickname;
    // 头像URL
    private String avatar;
    private String phone;
}