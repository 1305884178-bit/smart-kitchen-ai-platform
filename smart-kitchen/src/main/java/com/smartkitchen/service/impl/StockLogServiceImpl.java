package com.smartkitchen.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartkitchen.entity.StockLog;
import com.smartkitchen.mapper.StockLogMapper;
import com.smartkitchen.service.StockLogService;
import org.springframework.stereotype.Service;

/**
 * 库存变更流水服务实现类
 */
@Service
public class StockLogServiceImpl extends ServiceImpl<StockLogMapper, StockLog> implements StockLogService {
}
