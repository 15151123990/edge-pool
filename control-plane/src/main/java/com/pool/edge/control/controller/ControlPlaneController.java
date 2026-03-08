package com.pool.edge.control.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pool.edge.common.protocol.ArtifactMeta;
import com.pool.edge.common.protocol.HeartbeatRequest;
import com.pool.edge.common.protocol.OpsTask;
import com.pool.edge.common.protocol.TaskResult;
import com.pool.edge.control.service.InMemoryControlState;
import com.pool.edge.control.service.MqttTaskPublisher;
import com.pool.edge.control.service.SignatureService;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * 控制面 HTTP 控制器。
 * 提供心跳接收、任务下发/拉取、回执接收与附件元数据接收。
 */
@RestController
@RequestMapping("/cloud")
public class ControlPlaneController {
    private final InMemoryControlState state;
    private final SignatureService signatureService;
    private final MqttTaskPublisher mqttTaskPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 构造控制面控制器。
     *
     * @param state 内存态存储
     * @param signatureService 验签服务
     * @param mqttTaskPublisher MQTT 任务发布器（可为空）
     */
    public ControlPlaneController(InMemoryControlState state,
                                  SignatureService signatureService,
                                  @Nullable MqttTaskPublisher mqttTaskPublisher) {
        this.state = state;
        this.signatureService = signatureService;
        this.mqttTaskPublisher = mqttTaskPublisher;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 接收边缘心跳并验签。
     *
     * @param req 心跳请求体
     * @param deviceId 设备 ID 请求头
     * @param ts 请求时间戳
     * @param signature 请求签名
     * @return 处理结果
     */
    @PostMapping("/heartbeat")
    public String heartbeat(@RequestBody HeartbeatRequest req,
                            @RequestHeader("X-Device-Id") String deviceId,
                            @RequestHeader("X-Timestamp") String ts,
                            @RequestHeader("X-Signature") String signature) {
        String body = "{\"deviceId\":\"" + req.deviceId() + "\",\"ip\":\"" + req.ip() + "\",\"cpuUsage\":" + req.cpuUsage() +
                ",\"memUsage\":" + req.memUsage() + ",\"diskUsage\":" + req.diskUsage() + ",\"activeChannels\":" +
                req.activeChannels() + ",\"ts\":" + req.ts() + "}";
        if (!signatureService.verify(body, ts, deviceId, signature)) {
            throw new IllegalArgumentException("invalid signature");
        }
        state.saveHeartbeat(req);
        return "ok";
    }

    /**
     * 接收事件字符串（调试接口）。
     *
     * @param eventJson 事件 JSON
     * @return 处理结果
     */
    @PostMapping("/events")
    public String event(@RequestBody String eventJson) {
        state.saveEvent(eventJson);
        return "accepted";
    }

    /**
     * 创建运维任务并入队，若开启 MQTT 则同步推送。
     *
     * @param task 运维任务
     * @return 入队后的任务
     */
    @PostMapping("/tasks")
    public OpsTask task(@RequestBody OpsTask task) {
        OpsTask out = state.buildTask(task);
        if (mqttTaskPublisher != null) {
            mqttTaskPublisher.publishTask(out);
        }
        return out;
    }

    /**
     * 边缘端拉取任务（HTTP 兜底通道）。
     *
     * @param deviceId 设备 ID
     * @param headerDeviceId 请求头设备 ID
     * @param ts 请求时间戳
     * @param signature 请求签名
     * @return 任务列表
     */
    @GetMapping("/tasks/pull")
    public List<OpsTask> pullTasks(@RequestParam String deviceId,
                                   @RequestHeader("X-Device-Id") String headerDeviceId,
                                   @RequestHeader("X-Timestamp") String ts,
                                   @RequestHeader("X-Signature") String signature) {
        String body = "{\"deviceId\":\"" + deviceId + "\"}";
        if (!headerDeviceId.equals(deviceId) || !signatureService.verify(body, ts, headerDeviceId, signature)) {
            throw new IllegalArgumentException("invalid signature");
        }
        return state.pullTasks(deviceId, 10);
    }

    /**
     * 接收任务执行回执并验签。
     *
     * @param result 回执体
     * @param deviceId 设备 ID 请求头
     * @param ts 请求时间戳
     * @param signature 请求签名
     * @return 处理结果
     */
    @PostMapping("/task/result")
    public String taskResult(@RequestBody TaskResult result,
                             @RequestHeader("X-Device-Id") String deviceId,
                             @RequestHeader("X-Timestamp") String ts,
                             @RequestHeader("X-Signature") String signature) {
        String body = "{\"taskId\":\"" + result.taskId() + "\",\"deviceId\":\"" + result.deviceId() + "\",\"status\":\"" +
                result.status() + "\",\"message\":\"" + result.message().replace("\"", "'") + "\",\"ts\":" + result.ts() + "}";
        if (!signatureService.verify(body, ts, deviceId, signature)) {
            throw new IllegalArgumentException("invalid signature");
        }
        state.saveTaskResult(result);
        return "ok";
    }


    /**
     * 接收附件元数据（边缘端已直传 OSS）。
     *
     * @param body 元数据 JSON 原文
     * @param deviceId 设备 ID 请求头
     * @param ts 请求时间戳
     * @param signature 请求签名
     * @return 处理结果
     */
    @PostMapping("/artifacts/meta")
    public String artifactMeta(@RequestBody String body,
                               @RequestHeader("X-Device-Id") String deviceId,
                               @RequestHeader("X-Timestamp") String ts,
                               @RequestHeader("X-Signature") String signature) {
        if (!signatureService.verify(body, ts, deviceId, signature)) {
            throw new IllegalArgumentException("invalid signature");
        }
        try {
            // 解析元数据并保存索引信息
            ArtifactMeta meta = objectMapper.readValue(body, ArtifactMeta.class);
            String desc = "device=" + meta.deviceId() +
                    ", eventId=" + meta.eventId() +
                    ", level=" + meta.alertLevel() +
                    ", screenshotUrl=" + meta.screenshotUrl() +
                    ", clipUrl=" + meta.clipUrl();
            state.saveArtifact(desc);
            return "accepted";
        } catch (Exception e) {
            throw new IllegalStateException("parse artifact meta failed", e);
        }
    }

    /**
     * 查询设备心跳快照。
     *
     * @return 设备状态映射
     */
    @GetMapping("/devices")
    public Map<String, HeartbeatRequest> devices() {
        return state.heartbeatByDevice();
    }

    /**
     * 查询事件队列。
     *
     * @return 事件队列
     */
    @GetMapping("/events")
    public Queue<String> events() {
        return state.events();
    }

    /**
     * 查询任务回执队列。
     *
     * @return 任务回执队列
     */
    @GetMapping("/task/results")
    public Queue<TaskResult> taskResults() {
        return state.taskResults();
    }

    /**
     * 查询附件元数据队列。
     *
     * @return 附件元数据队列
     */
    @GetMapping("/artifacts")
    public Queue<String> artifacts() {
        return state.artifacts();
    }
}
