package com.pool.edge.alert.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;
import java.net.URI;

/**
 * S3/MinIO 存储实现。
 * 适合私有部署或兼容 S3 协议的对象存储。
 */
@Component
@ConditionalOnProperty(name = "edge.alert.storage.type", havingValue = "s3")
public class S3ArtifactStorage implements ArtifactStorage {
    @Value("${edge.alert.storage.s3.endpoint:http://127.0.0.1:9000}")
    private String endpoint;

    @Value("${edge.alert.storage.s3.region:us-east-1}")
    private String region;

    @Value("${edge.alert.storage.s3.bucket:pool-alerts}")
    private String bucket;

    @Value("${edge.alert.storage.s3.access-key:minioadmin}")
    private String accessKey;

    @Value("${edge.alert.storage.s3.secret-key:minioadmin}")
    private String secretKey;

    @Value("${edge.alert.storage.s3.public-base:http://127.0.0.1:9000}")
    private String publicBase;

    private volatile S3Client s3Client;

    @Override
    public String upload(File file, String objectKey) {
        // 1) 获取（或初始化）S3 客户端
        S3Client client = getClient();
        // 2) 组装上传请求并写入对象存储
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(detectContentType(file.getName()))
                .build();
        client.putObject(req, RequestBody.fromFile(file));
        return publicBase + "/" + bucket + "/" + objectKey;
    }

    private S3Client getClient() {
        if (s3Client != null) {
            return s3Client;
        }
        synchronized (this) {
            if (s3Client == null) {
                // 延迟初始化客户端，减少启动期依赖故障影响
                s3Client = S3Client.builder()
                        .endpointOverride(URI.create(endpoint))
                        .region(Region.of(region))
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKey, secretKey)))
                        .forcePathStyle(true)
                        .build();
            }
            return s3Client;
        }
    }

    private String detectContentType(String filename) {
        // 根据后缀标记 Content-Type
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (filename.endsWith(".mp4")) {
            return "video/mp4";
        }
        return "application/octet-stream";
    }
}
