package com.pool.edge.stream.api;

import com.pool.edge.common.model.ChannelConfig;
import com.pool.edge.common.model.FramePacket;

import java.util.Map;
import java.util.Optional;

/**
 * 拉流器接口。
 * 从指定通道拉取一帧数据，供推理引擎消费。
 */
public interface StreamPuller {
    /**
     * 拉取单帧。
     *
     * @param channel 通道配置
     * @return 帧数据（无帧时为空）
     */
    Optional<FramePacket> pull(ChannelConfig channel);

    /**
     * 释放指定通道的拉流资源。
     *
     * @param channelId 通道 ID
     */
    void release(String channelId);

    /**
     * 查询单通道拉流健康状态。
     *
     * @param channelId 通道 ID
     * @return 拉流健康状态
     */
    StreamHealthStatus status(String channelId);

    /**
     * 查询全部通道拉流健康状态。
     *
     * @return 以通道 ID 为键的状态映射
     */
    Map<String, StreamHealthStatus> allStatus();
}
