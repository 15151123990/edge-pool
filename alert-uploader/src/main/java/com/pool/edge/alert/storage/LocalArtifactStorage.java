package com.pool.edge.alert.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地文件系统存储实现。
 * 适合单机调试或离线演示场景。
 */
@Component
@ConditionalOnProperty(name = "edge.alert.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalArtifactStorage implements ArtifactStorage {
    @Value("${edge.alert.storage.local-dir:/tmp/pool-ai/uploaded}")
    private String localDir;

    @Override
    public String upload(File file, String objectKey) {
        try {
            // 按 objectKey 组织目录结构，便于后续迁移到对象存储
            Path target = Path.of(localDir, objectKey);
            Files.createDirectories(target.getParent());
            Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            return target.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException("local storage upload failed", e);
        }
    }
}
