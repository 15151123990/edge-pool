package com.pool.edge.infer.api;

import com.pool.edge.common.model.ChannelConfig;
import com.pool.edge.common.model.EventSignal;
import com.pool.edge.common.model.FramePacket;

import java.util.Optional;

/**
 * 推理引擎接口。
 * 输入帧数据，输出可用于规则判断的事件信号。
 */
public interface InferenceEngine {
    /**
     * 对单帧执行推理。
     *
     * @param frame 帧数据
     * @param channel 通道配置
     * @return 事件信号（无目标时为空）
     */
    Optional<EventSignal> infer(FramePacket frame, ChannelConfig channel);
}
