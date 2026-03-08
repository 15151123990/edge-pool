package com.pool.edge.agent.service;

import com.pool.edge.alert.api.AlertPipeline;
import com.pool.edge.common.model.ChannelConfig;
import com.pool.edge.event.api.RuleEngine;
import com.pool.edge.infer.api.InferenceEngine;
import com.pool.edge.stream.api.ChannelManager;
import com.pool.edge.stream.api.StreamPuller;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 主链路编排器。
 * 每轮调度执行：拉流 -> 推理 -> 规则判断 -> 告警上报。
 */
@Component
public class PipelineOrchestrator {
    private final ChannelManager channelManager;
    private final StreamPuller streamPuller;
    private final InferenceEngine inferenceEngine;
    private final RuleEngine ruleEngine;
    private final AlertPipeline alertPipeline;

    /**
     * 构造编排器。
     *
     * @param channelManager 通道管理器
     * @param streamPuller 拉流器
     * @param inferenceEngine 推理引擎
     * @param ruleEngine 规则引擎
     * @param alertPipeline 告警管道
     */
    public PipelineOrchestrator(ChannelManager channelManager,
                                StreamPuller streamPuller,
                                InferenceEngine inferenceEngine,
                                RuleEngine ruleEngine,
                                AlertPipeline alertPipeline) {
        this.channelManager = channelManager;
        this.streamPuller = streamPuller;
        this.inferenceEngine = inferenceEngine;
        this.ruleEngine = ruleEngine;
        this.alertPipeline = alertPipeline;
    }

    /**
     * 周期运行主链路。
     * 默认 200ms 一轮，可按设备性能调节。
     */
    @Scheduled(fixedDelayString = "${edge.pipeline.interval-ms:200}")
    public void runPipeline() {
        for (ChannelConfig channel : channelManager.list()) {
            if (!channelManager.isActive(channel.channelId())) {
                continue;
            }

            // 1) 拉取单帧
            streamPuller.pull(channel).ifPresent(frame ->
                    // 2) 执行推理，生成事件信号
                    inferenceEngine.infer(frame, channel).ifPresent(signal ->
                            // 3) 执行规则判断
                            ruleEngine.evaluate(signal).ifPresent(decision ->
                                    // 4) 告警上报
                                    alertPipeline.handle(decision)
                            )
                    )
            );
        }
    }
}
