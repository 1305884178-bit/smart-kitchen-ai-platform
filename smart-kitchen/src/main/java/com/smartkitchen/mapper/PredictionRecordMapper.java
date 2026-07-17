package com.smartkitchen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartkitchen.entity.PredictionRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI备菜预测记录表 Mapper 接口
 */
@Mapper
public interface PredictionRecordMapper extends BaseMapper<PredictionRecord> {
}
