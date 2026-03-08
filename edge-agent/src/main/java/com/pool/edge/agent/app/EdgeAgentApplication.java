package com.pool.edge.agent.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 边缘节点启动入口。
 * 开启定时调度，用于心跳上报和任务拉取。
 */
@SpringBootApplication(scanBasePackages = "com.pool.edge")
@EnableScheduling
public class EdgeAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(EdgeAgentApplication.class, args);
    }
}
