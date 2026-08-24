package com.smartkitchen.dto;

import lombok.Data;

@Data
public class LogoutDTO {
    /** 可选：同时作废对应 Refresh Token */
    private String refreshToken;
    /** true 时吊销该用户全部设备上的 Token */
    private Boolean allDevices;
}
