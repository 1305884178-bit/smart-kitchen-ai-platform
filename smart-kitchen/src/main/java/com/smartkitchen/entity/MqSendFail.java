package com.smartkitchen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单延迟消息发送失败记录表实体类
 * 写入时机：convertAndSend 当场抛错，或 publisher confirm nack 异步重试 3 次仍失败。
 * 仅作告警与人工核查，取消正确性由「未支付超时扫表」兜底。
 */
@Data
@TableName("oms_mq_send_fail")
public class MqSendFail {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private String reason;
    private Integer retryCount;
    private LocalDateTime createTime;
}
