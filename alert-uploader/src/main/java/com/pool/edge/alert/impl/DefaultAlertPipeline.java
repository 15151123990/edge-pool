package com.pool.edge.alert.impl;

import com.pool.edge.alert.api.AlertPipeline;
import com.pool.edge.alert.storage.ArtifactStorage;
import com.pool.edge.common.model.EventDecision;
import com.pool.edge.common.protocol.ArtifactMeta;
import com.pool.edge.common.security.HmacSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认告警管道实现。
 * 处理步骤：去重 -> 生成附件 -> 上传对象存储 -> 上报元数据。
 */
@Component
public class DefaultAlertPipeline implements AlertPipeline {
    private final Map<String, Long> dedupeWindow = new ConcurrentHashMap<>();
    private final RestClient restClient = RestClient.builder().build();
    private final ArtifactStorage artifactStorage;

    @Value("${edge.cloud.base-url:http://127.0.0.1:19090}")
    private String cloudBaseUrl;

    @Value("${edge.device.id:edge-001}")
    private String deviceId;

    @Value("${edge.cloud.secret:dev-secret}")
    private String secret;

    @Value("${edge.alert.artifact-dir:/tmp/pool-ai/artifacts}")
    private String artifactDir;

    @Value("${edge.alert.ffmpeg.enabled:true}")
    private boolean ffmpegEnabled;

    @Value("${edge.alert.ffmpeg.bin:ffmpeg}")
    private String ffmpegBin;

    @Value("${edge.alert.ffmpeg.source:}")
    private String ffmpegSource;

    @Value("${edge.alert.ffmpeg.clip-seconds:10}")
    private int clipSeconds;

    public DefaultAlertPipeline(ArtifactStorage artifactStorage) {
        this.artifactStorage = artifactStorage;
    }

    @Override
    public void handle(EventDecision decision) {
        // 1) 先做告警去重，防止短时间重复上报同一目标
        String key = decision.channelId() + ":" + decision.trackId() + ":" + decision.eventType();
        long now = System.currentTimeMillis();
        long ttlMs = Duration.ofSeconds(20).toMillis();
        Long last = dedupeWindow.putIfAbsent(key, now);
        if (last != null && now - last < ttlMs) {
            return;
        }
        dedupeWindow.put(key, now);

        try {
            // 2) 产出截图和短片段（短片段支持 ffmpeg，失败降级）
            File screenshot = createScreenshot(decision);
            File clip = createClip(decision);

            // 3) 上传到对象存储并获取可访问地址
            String screenshotKey = "screenshots/" + datePrefix() + "/" + screenshot.getName();
            String clipKey = "clips/" + datePrefix() + "/" + clip.getName();
            String screenshotUrl = artifactStorage.upload(screenshot, screenshotKey);
            String clipUrl = artifactStorage.upload(clip, clipKey);

            // 4) 仅上报元数据到控制面
            uploadMeta(decision, screenshot, clip, screenshotUrl, clipUrl, screenshotKey, clipKey);
        } catch (Exception e) {
            System.err.println("[ALERT] upload failed: " + e.getMessage());
        }
    }

    /**
     * 生成截图文件（联调阶段使用占位图）。
     *
     * @param decision 事件决策
     * @return 截图文件
     * @throws Exception 生成失败时抛出
     */
    private File createScreenshot(EventDecision decision) throws Exception {
        File baseDir = new File(artifactDir);
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw new IllegalStateException("create artifact directory failed: " + artifactDir);
        }
        File out = new File(baseDir, decision.eventId() + ".jpg");
        BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 1280, 720);
        g.setColor(Color.GREEN);
        g.drawString("eventId=" + decision.eventId(), 30, 40);
        g.drawString("eventType=" + decision.eventType(), 30, 70);
        g.drawString("channelId=" + decision.channelId(), 30, 100);
        g.drawString("alertLevel=" + decision.alertLevel(), 30, 130);
        g.dispose();
        ImageIO.write(image, "jpg", out);
        return out;
    }

    /**
     * 生成短片段。
     * 优先通过 ffmpeg 裁剪，失败时降级为文本占位文件。
     *
     * @param decision 事件决策
     * @return 短片段文件或占位文件
     * @throws Exception 生成失败时抛出
     */
    private File createClip(EventDecision decision) throws Exception {
        if (ffmpegEnabled && ffmpegSource != null && !ffmpegSource.isBlank()) {
            File out = new File(artifactDir, decision.eventId() + ".mp4");
            // 调用 ffmpeg 裁剪固定时长的片段
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegBin,
                    "-y",
                    "-i", ffmpegSource,
                    "-t", String.valueOf(clipSeconds),
                    "-an",
                    "-c:v", "libx264",
                    out.getAbsolutePath()
            );
            Process p = pb.start();
            int code = p.waitFor();
            if (code == 0 && out.exists() && out.length() > 0) {
                return out;
            }
        }
        return createClipPlaceholder(decision);
    }

    /**
     * 生成短片段占位文件。
     *
     * @param decision 事件决策
     * @return 占位文件
     * @throws Exception 写文件失败时抛出
     */
    private File createClipPlaceholder(EventDecision decision) throws Exception {
        File out = new File(artifactDir, decision.eventId() + ".txt");
        try (FileWriter writer = new FileWriter(out)) {
            writer.write("placeholder for short clip (ffmpeg disabled/failed)\n");
            writer.write("eventId=" + decision.eventId() + "\n");
            writer.write("channelId=" + decision.channelId() + "\n");
            writer.write("decisionAt=" + decision.decisionAt() + "\n");
        }
        return out;
    }

    /**
     * 上报告警附件元数据。
     *
     * @param decision 事件决策
     * @param screenshot 截图文件
     * @param clip 短片段文件
     * @param screenshotUrl 截图 URL
     * @param clipUrl 短片段 URL
     * @param screenshotKey 截图对象键
     * @param clipKey 短片段对象键
     */
    private void uploadMeta(EventDecision decision,
                            File screenshot,
                            File clip,
                            String screenshotUrl,
                            String clipUrl,
                            String screenshotKey,
                            String clipKey) {
        ArtifactMeta metaObj = new ArtifactMeta(
                decision.eventId(),
                deviceId,
                decision.channelId(),
                decision.eventType().name(),
                decision.alertLevel().name(),
                screenshotUrl,
                screenshotKey,
                screenshot.length(),
                clipUrl,
                clipKey,
                clip.length(),
                decision.reason(),
                System.currentTimeMillis()
        );

        String ts = String.valueOf(System.currentTimeMillis());
        String meta = "{\"eventId\":\"" + metaObj.eventId() + "\",\"deviceId\":\"" + metaObj.deviceId() +
                "\",\"channelId\":\"" + metaObj.channelId() + "\",\"eventType\":\"" + metaObj.eventType() +
                "\",\"alertLevel\":\"" + metaObj.alertLevel() + "\",\"screenshotUrl\":\"" + metaObj.screenshotUrl() +
                "\",\"screenshotKey\":\"" + metaObj.screenshotKey() + "\",\"screenshotSize\":" + metaObj.screenshotSize() +
                ",\"clipUrl\":\"" + metaObj.clipUrl() + "\",\"clipKey\":\"" + metaObj.clipKey() +
                "\",\"clipSize\":" + metaObj.clipSize() + ",\"reason\":\"" + metaObj.reason().replace("\"", "'") +
                "\",\"ts\":" + metaObj.ts() + "}";
        String sign = HmacSigner.sign(secret, meta, ts, deviceId);

        // 使用签名头保护边云通信
        restClient.post()
                .uri(cloudBaseUrl + "/cloud/artifacts/meta")
                .header("X-Device-Id", deviceId)
                .header("X-Timestamp", ts)
                .header("X-Signature", sign)
                .body(metaObj)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * 返回当前日期前缀（yyyy-MM-dd），用于对象键分区。
     *
     * @return 日期前缀
     */
    private String datePrefix() {
        Instant now = Instant.now();
        return now.toString().substring(0, 10);
    }
}
