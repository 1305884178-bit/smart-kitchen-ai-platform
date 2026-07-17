package com.smartkitchen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartkitchen.entity.StockLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 库存变更流水表 Mapper 接口
 */
@Mapper
public interface StockLogMapper extends BaseMapper<StockLog> {
}
