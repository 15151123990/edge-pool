package com.pool.edge.control.service;

import com.pool.edge.common.protocol.HeartbeatRequest;
import com.pool.edge.common.protocol.OpsTask;
import com.pool.edge.common.protocol.TaskResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 控制面内存态存储。
 * 用于开发联调阶段模拟设备状态、任务队列和告警索引。
 */
@Service
public class InMemoryControlState {
    private final Map<String, HeartbeatRequest> heartbeatByDevice = new ConcurrentHashMap<>();
    private final Queue<String> events = new ConcurrentLinkedQueue<>();
    private final Map<String, Queue<OpsTask>> taskQueueByDevice = new ConcurrentHashMap<>();
    private final Queue<TaskResult> taskResults = new ConcurrentLinkedQueue<>();
    private final Queue<String> artifacts = new ConcurrentLinkedQueue<>();

    /**
     * 保存设备心跳。
     *
     * @param hb 心跳请求
     */
    public void saveHeartbeat(HeartbeatRequest hb) {
        heartbeatByDevice.put(hb.deviceId(), hb);
    }

    /**
     * 返回设备心跳快照。
     *
     * @return 设备心跳映射
     */
    public Map<String, HeartbeatRequest> heartbeatByDevice() {
        return heartbeatByDevice;
    }

    /**
     * 保存事件字符串。
     *
     * @param event 事件文本
     */
    public void saveEvent(String event) {
        events.add(event);
    }

    public Queue<String> events() {
        return events;
    }

    /**
     * 任务入队并返回任务体。
     *
     * @param task 运维任务
     * @return 原任务
     */
    public OpsTask buildTask(OpsTask task) {
        taskQueueByDevice.computeIfAbsent(task.deviceId(), k -> new ConcurrentLinkedQueue<>()).add(task);
        return task;
    }

    /**
     * 按设备拉取任务（出队）。
     *
     * @param deviceId 设备 ID
     * @param maxSize 最大拉取数量
     * @return 任务列表
     */
    public List<OpsTask> pullTasks(String deviceId, int maxSize) {
        Queue<OpsTask> queue = taskQueueByDevice.computeIfAbsent(deviceId, k -> new ConcurrentLinkedQueue<>());
        List<OpsTask> out = new ArrayList<>();
        for (int i = 0; i < maxSize; i++) {
            OpsTask task = queue.poll();
            if (task == null) {
                break;
            }
            out.add(task);
        }
        return out;
    }

    /**
     * 保存任务回执。
     *
     * @param result 任务执行结果
     */
    public void saveTaskResult(TaskResult result) {
        taskResults.add(result);
    }

    public Queue<TaskResult> taskResults() {
        return taskResults;
    }

    /**
     * 保存附件元数据描述。
     *
     * @param artifactDesc 附件描述
     */
    public void saveArtifact(String artifactDesc) {
        artifacts.add(artifactDesc);
    }

    public Queue<String> artifacts() {
        return artifacts;
    }
}
