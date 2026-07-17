package com.smartkitchen.common;

import lombok.Getter;

/**
 * AI 备菜预测记录状态枚举类
 */
@Getter
public enum PredictionStatusEnum {
    PENDING(0, "待确认"),
    CONFIRMED(1, "已确认");

    private final int code;
    private final String desc;

    /**
     * 构造方法
     * @param code 状态码
     * @param desc 状态描述
     */
    PredictionStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
