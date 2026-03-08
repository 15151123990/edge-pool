package com.pool.edge.agent.service;

import com.pool.edge.common.protocol.HeartbeatRequest;
import com.pool.edge.common.protocol.OpsTask;
import com.pool.edge.common.protocol.TaskResult;
import com.pool.edge.stream.api.ChannelManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.List;

/**
 * 边缘同步调度器。
 * 周期执行心跳上报和任务拉取。
 */
@Component
public class EdgeSyncScheduler {
    private final EdgeCloudClient cloudClient;
    private final OpsTaskService opsTaskService;
    private final ChannelManager channelManager;

    @Value("${edge.device.id:edge-001}")
    private String deviceId;

    /**
     * 构造调度器。
     *
     * @param cloudClient 控制面客户端
     * @param opsTaskService 任务执行服务
     * @param channelManager 通道管理器
     */
    public EdgeSyncScheduler(EdgeCloudClient cloudClient, OpsTaskService opsTaskService, ChannelManager channelManager) {
        this.cloudClient = cloudClient;
        this.opsTaskService = opsTaskService;
        this.channelManager = channelManager;
    }

    @Scheduled(fixedDelayString = "${edge.sync.heartbeat-ms:10000}")
    public void heartbeat() {
        // 1) 采集基础状态并构造心跳体
        String ip = "127.0.0.1";
        try {
            ip = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ignored) {
        }
        HeartbeatRequest req = new HeartbeatRequest(
                deviceId,
                ip,
                0.12,
                0.32,
                0.41,
                channelManager.list().size(),
                System.currentTimeMillis()
        );
        // 2) 调用控制面接口上报心跳
        cloudClient.sendHeartbeat(req);
    }

    @Scheduled(fixedDelayString = "${edge.sync.pull-task-ms:5000}")
    public void pullTasks() {
        // 1) 拉取任务列表
        List<OpsTask> tasks = cloudClient.pullTasks();
        for (OpsTask task : tasks) {
            // 2) 顺序执行任务
            String msg = opsTaskService.execute(task);
            // 3) 上报执行回执
            TaskResult result = new TaskResult(
                    task.taskId(),
                    task.deviceId(),
                    "SUCCESS",
                    msg,
                    System.currentTimeMillis()
            );
            cloudClient.reportTaskResult(result);
        }
    }
}
