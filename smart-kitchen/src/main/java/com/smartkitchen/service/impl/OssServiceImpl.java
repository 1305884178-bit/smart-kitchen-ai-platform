package com.smartkitchen.service.impl;

import com.smartkitchen.service.OssService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * OSS 文件上传服务实现类
 * 生产环境需引入 aliyun-java-sdk-sts 并通过 AssumeRole 签发临时凭证
 */
@Service
public class OssServiceImpl implements OssService {

    @Value("${smart-kitchen.oss.endpoint:oss-cn-hangzhou.aliyuncs.com}")
    private String endpoint;

    @Value("${smart-kitchen.oss.access-key-id:}")
    private String accessKeyId;

    @Value("${smart-kitchen.oss.access-key-secret:}")
    private String accessKeySecret;

    @Value("${smart-kitchen.oss.role-arn:}")
    private String roleArn;

    @Value("${smart-kitchen.oss.bucket:}")
    private String bucket;

    @Value("${smart-kitchen.oss.region:cn-hangzhou}")
    private String region;

    @Override
    public Map<String, String> getStsToken() {
        Map<String, String> result = new HashMap<>();
        result.put("region", region);
        result.put("bucket", bucket);
        result.put("endpoint", endpoint);

        if (accessKeyId == null || accessKeyId.isEmpty() || roleArn == null || roleArn.isEmpty()) {
            result.put("status", "unconfigured");
            result.put("message", "OSS STS未配置，请先配置RoleArn等环境变量后启用");
            return result;
        }

        // TODO: 生产环境使用 aliyun-java-sdk-sts 调用 AssumeRole API 获取临时凭证
        result.put("status", "configured");
        result.put("message", "OSS已配置，前端可使用返回的临时凭证直传");
        return result;
    }
}
