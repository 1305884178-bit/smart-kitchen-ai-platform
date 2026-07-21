package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import com.smartkitchen.dto.SeatVO;
import com.smartkitchen.service.SeatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 顾客端座位控制器
 */
@RestController
@RequestMapping("/api/seat")
public class SeatController {

    @Autowired
    private SeatService seatService;

    /**
     * 获取所有座位及其占用状态
     * 顾客在选座页面调用此接口，被占用的座位前端展示为灰色不可选
     *
     * @return 座位列表
     */
    @GetMapping("/available")
    public Result<List<SeatVO>> getAvailableSeats() {
        List<SeatVO> seats = seatService.getAvailableSeats();
        return Result.success(seats);
    }
}