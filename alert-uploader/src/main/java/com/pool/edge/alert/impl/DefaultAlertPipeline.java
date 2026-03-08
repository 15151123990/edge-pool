package com.pool.edge.alert.impl;

import com.pool.edge.alert.api.AlertPipeline;
import com.pool.edge.alert.storage.ArtifactStorage;
import com.pool.edge.common.model.EventDecision;
import com.pool.edge.common.protocol.ArtifactMeta;
import com.pool.edge.common.security.HmacSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 默认告警管道实现。
 * 处理步骤：去重 -> 生成附件 -> 上传对象存储 -> 上报元数据。
 */
@Component
public class DefaultAlertPipeline implements AlertPipeline {
    private static final Logger logger = Logger.getLogger(DefaultAlertPipeline.class.getName());
    private static final long DEDUPE_TTL_MS = Duration.ofSeconds(20).toMillis();

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
        String key = decision.channelId() + ":" + decision.trackId() + ":" + decision.eventType();
        long now = System.currentTimeMillis();
        Long last = dedupeWindow.putIfAbsent(key, now);
        if (last != null && now - last < DEDUPE_TTL_MS) {
            return;
        }
        dedupeWindow.put(key, now);

        try {
            File screenshot = createScreenshot(decision);
            File clip = createClip(decision);

            String screenshotKey = "screenshots/" + datePrefix() + "/" + screenshot.getName();
            String clipKey = "clips/" + datePrefix() + "/" + clip.getName();
            String screenshotUrl = artifactStorage.upload(screenshot, screenshotKey);
            String clipUrl = artifactStorage.upload(clip, clipKey);

            uploadMeta(decision, screenshot, clip, screenshotUrl, clipUrl, screenshotKey, clipKey);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[ALERT] upload failed: " + e.getMessage(), e);
        }
    }

    @Scheduled(fixedRate = 60_000)
    public void cleanupDedupeWindow() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = dedupeWindow.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (now - entry.getValue() > DEDUPE_TTL_MS) {
                it.remove();
            }
        }
    }

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

    private File createClip(EventDecision decision) throws Exception {
        if (ffmpegEnabled && ffmpegSource != null && !ffmpegSource.isBlank()) {
            File out = new File(artifactDir, decision.eventId() + ".mp4");
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

        restClient.post()
                .uri(cloudBaseUrl + "/cloud/artifacts/meta")
                .header("X-Device-Id", deviceId)
                .header("X-Timestamp", ts)
                .header("X-Signature", sign)
                .body(metaObj)
                .retrieve()
                .toBodilessEntity();
    }

    private String datePrefix() {
        Instant now = Instant.now();
        return now.toString().substring(0, 10);
    }
}
