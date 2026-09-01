package com.smartkitchen.common;

import lombok.Getter;

/**
 * 撤销原因枚举类
 */
@Getter
public enum CancelReasonEnum {
    MERCHANT_CANCEL("MERCHANT_CANCEL", "商家撤销"),
    PAY_TIMEOUT("PAY_TIMEOUT", "支付超时自动取消");

    private final String code;
    private final String desc;

    /**
     * 构造方法
     * @param code 状态码
     * @param desc 状态描述
     */
    CancelReasonEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
