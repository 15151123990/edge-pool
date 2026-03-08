package com.pool.edge.alert.storage;

import java.io.File;

/**
 * 附件存储抽象。
 * 支持本地、OSS、S3 等不同后端实现。
 */
public interface ArtifactStorage {
    /**
     * 上传文件并返回可访问地址或本地路径。
     *
     * @param file 待上传文件
     * @param objectKey 对象键（目录+文件名）
     * @return 上传后的访问地址或本地路径
     */
    String upload(File file, String objectKey);
}
