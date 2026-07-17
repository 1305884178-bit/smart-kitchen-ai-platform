package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端知识库控制器
 */
@RestController
@RequestMapping("/api/admin/knowledge")
public class AdminKnowledgeController {

    /**
     * 上传文档至知识库（内部调用 Python 处理接口）
     * @return 返回上传与处理结果
     */
    @PostMapping("/upload")
    public Result<Object> uploadDocument() {
        return Result.success(null);
    }
}
