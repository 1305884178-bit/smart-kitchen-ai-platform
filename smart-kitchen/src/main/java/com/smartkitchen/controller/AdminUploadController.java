package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import com.smartkitchen.service.OssService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端文件上传控制器（OSS STS临时凭证签发）
 */
@RestController
@RequestMapping("/api/admin/upload")
public class AdminUploadController {

    @Autowired
    private OssService ossService;

    /**
     * 获取阿里云OSS STS临时凭证，供前端直传图片使用
     * @return OSS配置信息
     */
    @GetMapping("/sts-token")
    public Result<Map<String, String>> getStsToken() {
        return Result.success(ossService.getStsToken());
    }
}
