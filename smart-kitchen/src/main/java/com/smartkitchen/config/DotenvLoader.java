package com.smartkitchen.config;

import io.github.cdimascio.dotenv.Dotenv;

import java.io.File;

/**
 * .env 文件加载工具
 * 在 Spring Boot 启动前调用，将 .env 变量注入为系统属性
 */
public class DotenvLoader {

    private DotenvLoader() {}

    /**
     * 加载项目根目录的 .env 文件，通过 System.setProperty 注入为系统属性
     */
    public static void load() {
        String[] searchDirs = {
            System.getProperty("user.dir"),
            System.getProperty("user.dir") + "/smart-kitchen",
        };

        for (String dir : searchDirs) {
            File envFile = new File(dir, ".env");
            if (envFile.exists()) {
                System.out.println("[Dotenv] 找到 .env 文件: " + envFile.getAbsolutePath());
                Dotenv dotenv = Dotenv.configure().directory(dir).load();
                dotenv.entries().forEach(e -> {
                    if (System.getProperty(e.getKey()) == null) {
                        System.setProperty(e.getKey(), e.getValue());
                    }
                });
                System.out.println("[Dotenv] 已加载 " + dotenv.entries().size() + " 个变量");
                return;
            }
        }
        System.out.println("[Dotenv] 未找到 .env 文件");
    }
}
