package com.pool.edge.infer.api;

import com.pool.edge.common.model.ModelSpec;

import java.util.Collection;
import java.util.Optional;

/**
 * 模型注册中心接口。
 * 管理模型版本注册、查询与回滚。
 */
public interface ModelRegistry {
    /** 注册模型。 */
    void register(ModelSpec spec);
    /** 按 profile + version 查询模型。 */
    Optional<ModelSpec> find(String profileId, String version);
    /** 列出已注册模型。 */
    Collection<ModelSpec> list();
    /** 回滚模型到指定版本。 */
    void rollback(String profileId, String toVersion);
}
