package com.smartkitchen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartkitchen.entity.MqSendFail;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单延迟消息发送失败记录表 Mapper 接口
 */
@Mapper
public interface MqSendFailMapper extends BaseMapper<MqSendFail> {
}
