package com.smartkitchen.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartkitchen.entity.OrderDetail;
import com.smartkitchen.mapper.OrderDetailMapper;
import com.smartkitchen.service.OrderDetailService;
import org.springframework.stereotype.Service;

@Service
public class OrderDetailServiceImpl extends ServiceImpl<OrderDetailMapper, OrderDetail> implements OrderDetailService {
}
