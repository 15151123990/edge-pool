package com.pool.edge.common.model;

/**
 * 模型规格描述。
 * 包含模型版本、校验码与下载位置等信息。
 *
 * @param modelProfileId 模型配置档 ID
 * @param version 模型版本
 * @param checksum 模型文件校验码
 * @param uri 模型下载地址
 * @param enabled 是否启用
 */
public record ModelSpec(
        String modelProfileId,
        String version,
        String checksum,
        String uri,
        boolean enabled
) {
}
