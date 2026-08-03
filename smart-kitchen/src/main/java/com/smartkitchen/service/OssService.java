package com.smartkitchen.service;

import java.util.Map;

/**
 * OSS 文件上传服务接口
 */
public interface OssService {

    /**
     * 获取 STS 临时凭证（含 OSS 配置信息）
     * @return 凭证及配置信息
     */
    Map<String, String> getStsToken();
}
