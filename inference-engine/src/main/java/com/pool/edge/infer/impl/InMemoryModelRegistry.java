package com.pool.edge.infer.impl;

import com.pool.edge.common.model.ModelSpec;
import com.pool.edge.infer.api.ModelRegistry;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的模型注册中心实现。
 * 支持模型注册、查询和回滚校验。
 */
@Component
public class InMemoryModelRegistry implements ModelRegistry {
    private final Map<String, ModelSpec> cache = new ConcurrentHashMap<>();

    @Override
    public void register(ModelSpec spec) {
        // 按 profile:version 唯一键存储
        cache.put(key(spec.modelProfileId(), spec.version()), spec);
    }

    @Override
    public Optional<ModelSpec> find(String profileId, String version) {
        return Optional.ofNullable(cache.get(key(profileId, version)));
    }

    @Override
    public Collection<ModelSpec> list() {
        return cache.values();
    }

    @Override
    public void rollback(String profileId, String toVersion) {
        // TODO: 切换 profile 当前生效版本，并同步刷新通道绑定
        find(profileId, toVersion).orElseThrow(() -> new IllegalArgumentException("target version not found"));
    }

    /**
     * 生成模型缓存键。
     *
     * @param profileId 模型配置档 ID
     * @param version 模型版本
     * @return 缓存键
     */
    private String key(String profileId, String version) {
        return profileId + ":" + version;
    }
}
