package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import com.smartkitchen.service.OssService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 管理端文件上传控制器
 */
@RestController
@RequestMapping("/api/admin/upload")
public class AdminUploadController {

    @Autowired
    private OssService ossService;

    /**
     * 上传图片到 OSS（服务端代理上传，避免前端直传跨域问题）
     * @param file 图片文件
     * @return 图片 URL
     */
    @PostMapping("/image")
    public Result<String> uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            String url = ossService.uploadImage(file);
            return Result.success(url);
        } catch (Exception e) {
            return Result.error(500, "图片上传失败: " + e.getMessage());
        }
    }

    /**
     * 获取阿里云OSS STS临时凭证，供前端直传图片使用
     * @return OSS配置信息
     */
    @GetMapping("/sts-token")
    public Result<Map<String, String>> getStsToken() {
        return Result.success(ossService.getStsToken());
    }
}
