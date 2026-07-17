package com.smartkitchen.common;

import lombok.Getter;

/**
 * 订单明细加菜状态枚举类
 */
@Getter
public enum OrderDetailAddedEnum {
    FIRST_ORDER(0, "首单"),
    ADDED(1, "加菜追加");

    private final int code;
    private final String desc;

    /**
     * 构造方法
     * @param code 状态码
     * @param desc 状态描述
     */
    OrderDetailAddedEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
