package com.smartkitchen.service.impl;

import com.smartkitchen.dto.SeatVO;
import com.smartkitchen.mapper.OrderMapper;
import com.smartkitchen.service.SeatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 座位模块 Service 实现类
 */
@Service
public class SeatServiceImpl implements SeatService {

    @Autowired
    private OrderMapper orderMapper;

    // 硬编码全部座位列表（A01-A05, B01-B05）
    private static final List<String> ALL_SEATS = List.of(
            "A01", "A02", "A03", "A04", "A05",
            "B01", "B02", "B03", "B04", "B05"
    );

    @Override
    public List<SeatVO> getAvailableSeats() {
        // 1. 从数据库查询所有被占用的座位号
        List<String> occupiedSeatList = orderMapper.selectOccupiedSeats();
        
        // 使用 Set 提高查询效率（如果被占用的座位号有重复的也会自动去重）
        Set<String> occupiedSeatSet = new HashSet<>();
        if (occupiedSeatList != null) {
            occupiedSeatSet.addAll(occupiedSeatList);
        }

        // 2. 遍历全部座位，构造 SeatVO
        List<SeatVO> result = new ArrayList<>();
        for (String seatNumber : ALL_SEATS) {
            SeatVO vo = new SeatVO();
            vo.setSeatNumber(seatNumber);
            // 如果该座位号在被占用的 Set 中，说明被占用
            vo.setOccupied(occupiedSeatSet.contains(seatNumber));
            result.add(vo);
        }

        return result;
    }
}