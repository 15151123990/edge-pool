package com.pool.edge.alert.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;

/**
 * 阿里云 OSS 存储实现。
 * 用于生产环境上传截图和短片段。
 */
@Component
@ConditionalOnProperty(name = "edge.alert.storage.type", havingValue = "oss")
public class AliyunOssArtifactStorage implements ArtifactStorage {
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

    @Override
    public String upload(File file, String objectKey) {
        // 每次上传创建 OSS 客户端，避免长连接泄漏
        OSS client = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try (FileInputStream input = new FileInputStream(file)) {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(detectContentType(file.getName()));
            client.putObject(bucket, objectKey, input, metadata);
            return publicBase + "/" + objectKey;
        } catch (Exception e) {
            throw new IllegalStateException("aliyun oss upload failed", e);
        } finally {
            client.shutdown();
        }
    }

    private String detectContentType(String filename) {
        // 根据扩展名设置 Content-Type，便于浏览器直接预览
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (filename.endsWith(".mp4")) {
            return "video/mp4";
        }
        return "application/octet-stream";
    }
}
