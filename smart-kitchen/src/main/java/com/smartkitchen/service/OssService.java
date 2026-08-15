package com.smartkitchen.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * OSS 文件上传服务接口
 */
public interface OssService {

    /**
     * 上传图片到 OSS（服务端直传）
     * @param file 图片文件
     * @return 图片公网访问 URL
     */
    String uploadImage(MultipartFile file) throws IOException;

    /**
     * 获取 STS 临时凭证（含 OSS 配置信息）
     * @return 凭证及配置信息
     */
    Map<String, String> getStsToken();
}
