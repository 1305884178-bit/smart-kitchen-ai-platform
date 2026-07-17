package com.smartkitchen.common;

import lombok.Getter;

/**
 * 菜品状态枚举类
 */
@Getter
public enum DishStatusEnum {
    OFF_SALE(0, "停售"),
    ON_SALE(1, "起售");

    private final int code;
    private final String desc;

    /**
     * 构造方法
     * @param code 状态码
     * @param desc 状态描述
     */
    DishStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
