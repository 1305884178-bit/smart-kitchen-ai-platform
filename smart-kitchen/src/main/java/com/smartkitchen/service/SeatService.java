package com.smartkitchen.service;

import com.smartkitchen.dto.SeatVO;

import java.util.List;

/**
 * 座位模块 Service 接口
 */
public interface SeatService {
    /**
     * 获取所有座位及其占用状态
     *
     * @return 座位列表
     */
    List<SeatVO> getAvailableSeats();
}