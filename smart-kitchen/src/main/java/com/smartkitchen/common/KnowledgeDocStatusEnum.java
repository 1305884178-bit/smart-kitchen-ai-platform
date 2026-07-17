package com.smartkitchen.common;

import lombok.Getter;

/**
 * 知识库文档状态枚举类
 */
@Getter
public enum KnowledgeDocStatusEnum {
    DRAFT("draft", "草稿"),
    ACTIVE("active", "生效"),
    ARCHIVED("archived", "归档");

    private final String code;
    private final String desc;

    /**
     * 构造方法
     * @param code 状态码
     * @param desc 状态描述
     */
    KnowledgeDocStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
