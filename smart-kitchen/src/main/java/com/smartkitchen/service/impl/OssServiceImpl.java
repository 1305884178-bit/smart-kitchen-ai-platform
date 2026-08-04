package com.smartkitchen.service.impl;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.auth.sts.AssumeRoleRequest;
import com.aliyuncs.auth.sts.AssumeRoleResponse;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.http.MethodType;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.profile.IClientProfile;
import com.smartkitchen.service.OssService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * OSS 文件上传服务实现类
 * 通过 STS AssumeRole API 为前端签发临时上传凭证，实现客户端直传 OSS
 */
@Service
public class OssServiceImpl implements OssService {

    private static final Logger log = LoggerFactory.getLogger(OssServiceImpl.class);

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

    @Value("${smart-kitchen.oss.endpoint:oss-cn-hangzhou.aliyuncs.com}")
    private String endpoint;

    /**
     * 调用 STS AssumeRole 获取临时凭证，供前端直传 OSS
     * @return 包含临时 AK/SK/SecurityToken 及 OSS 基础配置的 Map
     */
    @Override
    public Map<String, String> getStsToken() {
        Map<String, String> result = new HashMap<>();

        // 基础信息始终返回，方便前端做兜底展示
        result.put("region", region);
        result.put("bucket", bucket);
        result.put("endpoint", endpoint);

        // 校验必需配置
        if (isEmpty(accessKeyId) || isEmpty(accessKeySecret) || isEmpty(roleArn) || isEmpty(bucket)) {
            result.put("status", "unconfigured");
            result.put("message", "OSS STS 未完整配置，请检查 ACCESS_KEY_ID / ACCESS_KEY_SECRET / ROLE_ARN / BUCKET");
            return result;
        }

        try {
            // 创建 STS 客户端
            IClientProfile profile = DefaultProfile.getProfile(region, accessKeyId, accessKeySecret);
            DefaultAcsClient client = new DefaultAcsClient(profile);

            // 构造 AssumeRole 请求
            AssumeRoleRequest request = new AssumeRoleRequest();
            request.setSysMethod(MethodType.POST);
            request.setRoleArn(roleArn);
            request.setRoleSessionName("smart-kitchen-upload");
            // 策略：限制仅可向指定 Bucket 执行 PutObject，遵循最小权限原则
            request.setPolicy("{\n" +
                    "  \"Version\": \"1\",\n" +
                    "  \"Statement\": [\n" +
                    "    {\n" +
                    "      \"Effect\": \"Allow\",\n" +
                    "      \"Action\": [\"oss:PutObject\"],\n" +
                    "      \"Resource\": [\"acs:oss:*:*:" + bucket + "/*\"]\n" +
                    "    }\n" +
                    "  ]\n" +
                    "}");
            request.setDurationSeconds(3600L);

            // 调用 AssumeRole API
            AssumeRoleResponse response = client.getAcsResponse(request);

            // 组装返回结果
            result.put("status", "success");
            result.put("accessKeyId", response.getCredentials().getAccessKeyId());
            result.put("accessKeySecret", response.getCredentials().getAccessKeySecret());
            result.put("securityToken", response.getCredentials().getSecurityToken());
            result.put("expiration", response.getCredentials().getExpiration());

        } catch (ClientException e) {
            log.error("STS AssumeRole 调用失败: {}", e.getErrMsg(), e);
            result.put("status", "error");
            result.put("message", "获取上传凭证失败: " + e.getErrMsg());
        }

        return result;
    }

    /**
     * 判断字符串是否为空
     * @param str 待校验字符串
     * @return true 表示为空
     */
    private boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }
}
