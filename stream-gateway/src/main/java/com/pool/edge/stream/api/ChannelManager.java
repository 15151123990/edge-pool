package com.pool.edge.stream.api;

import com.pool.edge.common.model.ChannelConfig;

import java.util.Collection;
import java.util.Optional;

/**
 * 通道管理接口。
 * 负责通道增删改查与启停控制。
 */
public interface ChannelManager {
    /** 新增或更新通道配置。 */
    void addOrUpdate(ChannelConfig config);
    /** 删除通道。 */
    void remove(String channelId);
    /** 启动通道拉流任务。 */
    void start(String channelId);
    /** 停止通道拉流任务。 */
    void stop(String channelId);
    /** 查询单个通道。 */
    Optional<ChannelConfig> get(String channelId);
    /** 查询全部通道。 */
    Collection<ChannelConfig> list();
    /** 判断通道是否处于启用状态。 */
    boolean isActive(String channelId);
}
