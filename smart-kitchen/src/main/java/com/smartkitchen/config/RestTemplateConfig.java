package com.smartkitchen.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Configuration
public class RestTemplateConfig {

    /**
     * 创建并配置RestTemplate实例，用于发送HTTP请求
     * 配置了请求工厂的超时参数，避免请求长时间挂起
     * @return 配置完成的RestTemplate对象，会被Spring容器管理
     */
    @Bean
    public RestTemplate restTemplate() {
        // 创建简单客户端HTTP请求工厂，用于处理底层HTTP连接
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(10000); // 10秒：读取服务器响应数据的超时时间
        factory.setConnectTimeout(5000); // 5秒：与服务器建立连接的超时时间
        // 使用配置好的请求工厂创建RestTemplate实例
        return new RestTemplate(factory);
    }
}
