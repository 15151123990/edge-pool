package com.pool.edge.alert.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.util.logging.Logger;

/**
 * 阿里云 OSS 存储实现。
 * 用于生产环境上传截图和短片段。
 */
@Component
@ConditionalOnProperty(name = "edge.alert.storage.type", havingValue = "oss")
public class AliyunOssArtifactStorage implements ArtifactStorage {
    private static final Logger logger = Logger.getLogger(AliyunOssArtifactStorage.class.getName());

    @Value("${edge.alert.storage.oss.endpoint:oss-cn-shenzhen.aliyuncs.com}")
    private String endpoint;

    @Value("${edge.alert.storage.oss.bucket:pool-alerts}")
    private String bucket;

    @Value("${edge.alert.storage.oss.access-key-id:}")
    private String accessKeyId;

    @Value("${edge.alert.storage.oss.access-key-secret:}")
    private String accessKeySecret;

    @Value("${edge.alert.storage.oss.public-base:https://pool-alerts.oss-cn-shenzhen.aliyuncs.com}")
    private String publicBase;

    private volatile OSS client;

    @PostConstruct
    public void init() {
        if (accessKeyId == null || accessKeyId.isBlank() || accessKeySecret == null || accessKeySecret.isBlank()) {
            logger.warning("[OSS] access key not configured, OSS uploader disabled");
            return;
        }
        client = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        logger.info("[OSS] client initialized, endpoint=" + endpoint + ", bucket=" + bucket);
    }

    @PreDestroy
    public void destroy() {
        if (client != null) {
            client.shutdown();
            logger.info("[OSS] client shutdown");
        }
    }

    @Override
    public String upload(File file, String objectKey) {
        if (client == null) {
            throw new IllegalStateException("aliyun oss is not initialized, please configure access key");
        }
        try (FileInputStream input = new FileInputStream(file)) {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(detectContentType(file.getName()));
            client.putObject(bucket, objectKey, input, metadata);
            return publicBase + "/" + objectKey;
        } catch (Exception e) {
            throw new IllegalStateException("aliyun oss upload failed", e);
        }
    }

    private String detectContentType(String filename) {
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (filename.endsWith(".mp4")) {
            return "video/mp4";
        }
        return "application/octet-stream";
    }
}
