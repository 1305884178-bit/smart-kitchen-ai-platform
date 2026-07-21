package com.smartkitchen.dto;

import lombok.Data;

@Data
public class SeatVO {
    private String seatNumber;  // 座位号，如 "A01"
    private Boolean occupied;   // 是否被占用：true=被占用，false=空闲
}
