package com.smartkitchen.service.impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.smartkitchen.service.OssService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * OSS 文件上传服务实现类
 * 服务端直接上传到 OSS，避免前端直传的跨域问题
 */
@Service
public class OssServiceImpl implements OssService {

    private static final Logger log = LoggerFactory.getLogger(OssServiceImpl.class);

    @Value("${smart-kitchen.oss.access-key-id:}")
    private String accessKeyId;

    @Value("${smart-kitchen.oss.access-key-secret:}")
    private String accessKeySecret;

    @Value("${smart-kitchen.oss.bucket:}")
    private String bucket;

    @Value("${smart-kitchen.oss.endpoint:oss-cn-hangzhou.aliyuncs.com}")
    private String endpoint;

    /**
     * 上传图片到 OSS
     * @param file 前端上传的图片文件
     * @return 图片公网访问 URL
     */
    @Override
    public String uploadImage(MultipartFile file) throws IOException {
        if (isEmpty(accessKeyId) || isEmpty(accessKeySecret) || isEmpty(bucket)) {
            throw new IllegalStateException("OSS 未完整配置");
        }

        String ext = getExtension(file.getOriginalFilename());
        String objectName = "dishes/" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 6) + ext;

        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try {
            ossClient.putObject(bucket, objectName, file.getInputStream());
            log.info("图片上传成功: {}", objectName);
        } finally {
            ossClient.shutdown();
        }

        return "https://" + bucket + "." + endpoint + "/" + objectName;
    }

    /**
     * 返回 OSS 直传凭证（含 OSS 配置信息）
     * 未完整配置时返回 unconfigured 状态，供前端判断是否可用直传
     * @return OSS 配置 Map
     */
    @Override
    public Map<String, String> getStsToken() {
        Map<String, String> result = new HashMap<>();
        String region = extractRegion(endpoint);
        if (isEmpty(accessKeyId) || isEmpty(accessKeySecret) || isEmpty(bucket)) {
            result.put("status", "unconfigured");
            result.put("region", region);
            result.put("bucket", bucket == null ? "" : bucket);
            return result;
        }
        result.put("status", "success");
        result.put("region", region);
        result.put("bucket", bucket);
        return result;
    }

    /**
     * 从 OSS endpoint 中解析地域，如 oss-cn-hangzhou.aliyuncs.com 解析为 cn-hangzhou
     * @param endpoint OSS 访问域名
     * @return 地域字符串
     */
    private String extractRegion(String endpoint) {
        if (endpoint == null) {
            return "";
        }
        String s = endpoint;
        if (s.startsWith("oss-")) {
            s = s.substring(4);
        }
        int dot = s.indexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf("."));
    }

    private boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }
}
