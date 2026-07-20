package com.smartkitchen.dto;

import lombok.Data;

@Data
public class LoginVO {
    private String token;
    private Long userId;
    private String role;
    private Boolean needRegister;
}