package com.pool.edge.stream.impl;

import com.pool.edge.common.model.ChannelConfig;
import com.pool.edge.stream.api.ChannelManager;
import com.pool.edge.stream.api.StreamPuller;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的通道管理实现。
 * 当前用于骨架联调，后续可替换为数据库持久化实现。
 */
@Component
public class InMemoryChannelManager implements ChannelManager {
    private final Map<String, ChannelConfig> channels = new ConcurrentHashMap<>();
    private final Set<String> activeChannels = ConcurrentHashMap.newKeySet();
    private final StreamPuller streamPuller;

    public InMemoryChannelManager(StreamPuller streamPuller) {
        this.streamPuller = streamPuller;
    }

    @Override
    public void addOrUpdate(ChannelConfig config) {
        // 1) 按通道 ID 覆盖写入配置
        channels.put(config.channelId(), config);
    }

    @Override
    public void remove(String channelId) {
        channels.remove(channelId);
        activeChannels.remove(channelId);
        streamPuller.release(channelId);
    }

    @Override
    public void start(String channelId) {
        // 2) 标记通道为启用态，后续由编排器执行拉流与推理
        if (channels.containsKey(channelId)) {
            activeChannels.add(channelId);
        }
    }

    @Override
    public void stop(String channelId) {
        // 3) 取消启用态，编排器不再调度该通道
        activeChannels.remove(channelId);
        streamPuller.release(channelId);
    }

    @Override
    public Optional<ChannelConfig> get(String channelId) {
        return Optional.ofNullable(channels.get(channelId));
    }

    @Override
    public Collection<ChannelConfig> list() {
        return channels.values();
    }

    @Override
    public boolean isActive(String channelId) {
        return activeChannels.contains(channelId);
    }
}
