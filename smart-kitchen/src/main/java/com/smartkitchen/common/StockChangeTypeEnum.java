package com.smartkitchen.common;

import lombok.Getter;

/**
 * 库存变更类型枚举类
 */
@Getter
public enum StockChangeTypeEnum {
    RESERVE("RESERVE", "预扣"),
    DEDUCT("DEDUCT", "扣减"),
    ROLLBACK("ROLLBACK", "回滚"),
    MANUAL("MANUAL", "人工调整");

    private final String code;
    private final String desc;

    /**
     * 构造方法
     * @param code 状态码
     * @param desc 状态描述
     */
    StockChangeTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
