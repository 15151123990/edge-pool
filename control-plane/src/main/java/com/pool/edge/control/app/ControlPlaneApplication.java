package com.pool.edge.control.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 控制面启动入口。
 * 提供设备管理、任务编排、告警元数据接收等能力。
 */
@SpringBootApplication(scanBasePackages = "com.pool.edge")
public class ControlPlaneApplication {
    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneApplication.class, args);
    }
}
