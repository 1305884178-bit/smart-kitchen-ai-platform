package com.smartkitchen.common;

import lombok.Getter;

/**
 * 订单状态枚举类
 * 用于统一管理订单状态，替代魔法数字
 */
// Lombok的@Getter注解，自动为枚举类中的所有final字段生成标准getter方法（getCode()和getDesc()），避免手动编写重复的取值方法代码
@Getter
public enum OrderStatusEnum {
    ORDERED(0, "已下单"),
    SERVED(10, "已上菜，待结账"),
    PAID(20, "已结账"),
    CANCELLED(90, "已撤销");

    private final int code;
    private final String desc;

    /**
     * 构造方法
     * @param code 状态码
     * @param desc 状态描述
     */
    OrderStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
