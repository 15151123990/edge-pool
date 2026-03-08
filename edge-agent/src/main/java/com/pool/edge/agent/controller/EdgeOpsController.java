package com.pool.edge.agent.controller;

import com.pool.edge.alert.api.AlertPipeline;
import com.pool.edge.agent.service.OpsTaskService;
import com.pool.edge.common.model.Enums;
import com.pool.edge.common.model.ChannelConfig;
import com.pool.edge.common.model.EventDecision;
import com.pool.edge.common.model.FramePacket;
import com.pool.edge.common.model.ModelSpec;
import com.pool.edge.common.protocol.OpsTask;
import com.pool.edge.infer.api.ModelRegistry;
import com.pool.edge.stream.api.ChannelManager;
import com.pool.edge.stream.api.StreamHealthStatus;
import com.pool.edge.stream.api.StreamPuller;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 边缘节点管理接口。
 * 提供通道管理、任务执行和告警联调能力。
 */
@RestController
@RequestMapping("/edge")
public class EdgeOpsController {
    private final ChannelManager channelManager;
    private final OpsTaskService opsTaskService;
    private final AlertPipeline alertPipeline;
    private final ModelRegistry modelRegistry;
    private final StreamPuller streamPuller;

    /**
     * 构造控制器。
     *
     * @param channelManager 通道管理器
     * @param opsTaskService 运维任务执行服务
     * @param alertPipeline 告警管道
     * @param modelRegistry 模型注册中心
     * @param streamPuller 拉流器
     */
    public EdgeOpsController(ChannelManager channelManager,
                             OpsTaskService opsTaskService,
                             AlertPipeline alertPipeline,
                             ModelRegistry modelRegistry,
                             StreamPuller streamPuller) {
        this.channelManager = channelManager;
        this.opsTaskService = opsTaskService;
        this.alertPipeline = alertPipeline;
        this.modelRegistry = modelRegistry;
        this.streamPuller = streamPuller;
    }

    /**
     * 新增或更新通道。
     *
     * @param cfg 通道配置
     */
    @PostMapping("/channels")
    public void addChannel(@RequestBody ChannelConfig cfg) {
        channelManager.addOrUpdate(cfg);
    }

    /**
     * 注册模型规格（本地文件路径）。
     *
     * @param spec 模型规格
     * @return 处理结果
     */
    @PostMapping("/models")
    public String registerModel(@RequestBody ModelSpec spec) {
        if (modelRegistry == null) {
            return "model registry not available";
        }
        modelRegistry.register(spec);
        return "model registered";
    }

    /**
     * 查询全部通道。
     *
     * @return 通道列表
     */
    @GetMapping("/channels")
    public Collection<ChannelConfig> channels() {
        return channelManager.list();
    }

    /**
     * 抓取指定通道当前快照。
     * 用于前端绘制 ROI。
     *
     * @param channelId 通道 ID
     * @return JPEG 图片字节
     */
    @GetMapping("/channels/{channelId}/snapshot")
    public ResponseEntity<byte[]> snapshot(@PathVariable String channelId) {
        Optional<ChannelConfig> cfgOpt = channelManager.get(channelId);
        if (cfgOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(("channel not found: " + channelId).getBytes());
        }
        Optional<FramePacket> frameOpt = streamPuller.pull(cfgOpt.get());
        if (frameOpt.isEmpty() || frameOpt.get().image() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(("snapshot unavailable: " + channelId).getBytes());
        }
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(frameOpt.get().image(), "jpg", bos);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                    .body(bos.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("snapshot encode failed: " + e.getMessage()).getBytes());
        }
    }

    /**
     * 查询所有通道拉流健康状态。
     *
     * @return 通道拉流健康状态映射
     */
    @GetMapping("/stream-status")
    public Map<String, StreamHealthStatus> allStreamStatus() {
        return streamPuller.allStatus();
    }

    /**
     * 查询指定通道拉流健康状态。
     *
     * @param channelId 通道 ID
     * @return 指定通道拉流状态
     */
    @GetMapping("/channels/{channelId}/stream-status")
    public StreamHealthStatus streamStatus(@PathVariable String channelId) {
        return streamPuller.status(channelId);
    }

    /**
     * 更新指定通道 ROI 多边形配置。
     *
     * @param channelId 通道 ID
     * @param req ROI 更新请求
     * @return 处理结果
     */
    @PutMapping("/channels/{channelId}/roi")
    public String updateRoi(@PathVariable String channelId, @RequestBody RoiUpdateRequest req) {
        Optional<ChannelConfig> cfgOpt = channelManager.get(channelId);
        if (cfgOpt.isEmpty()) {
            return "channel not found";
        }
        ChannelConfig old = cfgOpt.get();
        ChannelConfig updated = new ChannelConfig(
                old.channelId(),
                old.streamUrl(),
                old.modelProfileId(),
                old.modelVersion(),
                old.sampleFps(),
                req.roiPolygons()
        );
        channelManager.addOrUpdate(updated);
        return "roi updated";
    }

    /**
     * 启动指定通道。
     *
     * @param channelId 通道 ID
     * @return 处理结果
     */
    @PostMapping("/channels/{channelId}/start")
    public String startChannel(@PathVariable String channelId) {
        channelManager.start(channelId);
        return "channel started";
    }

    /**
     * 停止指定通道。
     *
     * @param channelId 通道 ID
     * @return 处理结果
     */
    @PostMapping("/channels/{channelId}/stop")
    public String stopChannel(@PathVariable String channelId) {
        channelManager.stop(channelId);
        return "channel stopped";
    }

    /**
     * 执行运维任务（本地接口）。
     *
     * @param task 运维任务
     * @return 执行结果
     */
    @PostMapping("/tasks")
    public String executeTask(@RequestBody OpsTask task) {
        return opsTaskService.execute(task);
    }

    /**
     * 触发一条模拟告警。
     *
     * @param channelId 通道 ID
     * @return 处理结果
     */
    @PostMapping("/mock-alert/{channelId}")
    public String mockAlert(@PathVariable String channelId) {
        long now = System.currentTimeMillis();
        EventDecision d = new EventDecision(
                UUID.randomUUID().toString(),
                Enums.EventType.LONG_SUBMERGE,
                Enums.AlertLevel.L2_ALARM,
                channelId,
                "track-demo",
                now,
                now,
                "manual mock event for integration test"
        );
        alertPipeline.handle(d);
        return "mock alert triggered";
    }

    /**
     * ROI 更新请求体。
     *
     * @param roiPolygons ROI 多边形字符串数组
     */
    public record RoiUpdateRequest(List<String> roiPolygons) {
    }

}
