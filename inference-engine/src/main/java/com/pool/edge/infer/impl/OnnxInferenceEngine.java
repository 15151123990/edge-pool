package com.pool.edge.infer.impl;

import com.pool.edge.common.model.ChannelConfig;
import com.pool.edge.common.model.EventSignal;
import com.pool.edge.common.model.FramePacket;
import com.pool.edge.common.model.ModelSpec;
import com.pool.edge.infer.api.InferenceEngine;
import com.pool.edge.infer.api.ModelRegistry;
import ai.onnxruntime.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 ONNX Runtime 的推理引擎实现。
 * 输入视频帧并执行 ONNX 模型推理，输出规则引擎可消费的 EventSignal。
 */
@Component
@Slf4j
public class OnnxInferenceEngine implements InferenceEngine {

    private final ModelRegistry modelRegistry;
    private final OrtEnvironment ortEnvironment;
    private final Map<String, OrtSession> sessionCache = new ConcurrentHashMap<>();
    private final Map<String, float[]> previousGray = new ConcurrentHashMap<>();

    @Value("${edge.infer.input-size:640}")
    private int inputSize;

    @Value("${edge.infer.conf-threshold:0.25}")
    private float confThreshold;

    public OnnxInferenceEngine(ModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry;
        this.ortEnvironment = OrtEnvironment.getEnvironment();
    }

    @Override
    public Optional<EventSignal> infer(FramePacket frame, ChannelConfig channel) {
        try {
            Optional<ModelSpec> modelSpecOpt = modelRegistry.find(channel.modelProfileId(), channel.modelVersion());
            if (modelSpecOpt.isEmpty()) {
                return Optional.empty();
            }
            ModelSpec modelSpec = modelSpecOpt.get();
            OrtSession session = getSession(modelSpec);
            if (session == null) {
                return Optional.empty();
            }

            BufferedImage image = frame.image();
            if (image == null) {
                return Optional.empty();
            }

            float[] input = preprocess(image, inputSize, inputSize);
            long[] shape = new long[]{1, 3, inputSize, inputSize};

            String inputName = session.getInputNames().iterator().next();
            try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(input), shape);
                 OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
                if (result.size() == 0) {
                    return Optional.empty();
                }
                float confidence = parseMaxConfidence(result.get(0));
                if (confidence < confThreshold) {
                    return Optional.empty();
                }

                float motionScore = estimateMotionScore(channel.channelId(), image);
                float speed = motionScore * 0.2f;
                boolean headVisible = confidence > 0.5f;
                boolean inDangerZone = !channel.roiPolygons().isEmpty() && motionScore > 0.7f;
                String trackId = "trk-" + (frame.frameIndex() % 4);

                return Optional.of(new EventSignal(
                        channel.channelId(),
                        trackId,
                        frame.timestampMs(),
                        confidence,
                        speed,
                        headVisible,
                        inDangerZone,
                        motionScore
                ));
            }
        } catch (OrtException e) {
            log.error("[INFER] ONNX inference error: {}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("[INFER] unexpected error: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    @PreDestroy
    public void destroy() {
        for (Map.Entry<String, OrtSession> entry : sessionCache.entrySet()) {
            try {
                entry.getValue().close();
                log.info("[INFER] session closed: {}", entry.getKey());
            } catch (OrtException e) {
                log.warn("[INFER] failed to close session {}: {}", entry.getKey(), e.getMessage());
            }
        }
        sessionCache.clear();
        previousGray.clear();
        log.info("[INFER] engine destroyed, all sessions released");
    }

    private OrtSession getSession(ModelSpec modelSpec) {
        return sessionCache.computeIfAbsent(cacheKey(modelSpec), k -> {
            try {
                OrtSession.SessionOptions options = new OrtSession.SessionOptions();
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                OrtSession session = ortEnvironment.createSession(modelSpec.uri(), options);
                log.info("[INFER] session created: {}", k);
                return session;
            } catch (OrtException e) {
                log.error("[INFER] failed to create session for {}: {}", k, e.getMessage());
                return null;
            }
        });
    }

    private String cacheKey(ModelSpec spec) {
        return spec.modelProfileId() + ":" + spec.version();
    }

    private float[] preprocess(BufferedImage src, int targetW, int targetH) {
        BufferedImage resized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = resized.createGraphics();
        g.drawImage(src, 0, 0, targetW, targetH, null);
        g.dispose();

        float[] chw = new float[3 * targetW * targetH];
        int idxR = 0;
        int idxG = targetW * targetH;
        int idxB = targetW * targetH * 2;

        for (int y = 0; y < targetH; y++) {
            for (int x = 0; x < targetW; x++) {
                int rgb = resized.getRGB(x, y);
                float r = ((rgb >> 16) & 0xFF) / 255.0f;
                float gch = ((rgb >> 8) & 0xFF) / 255.0f;
                float b = (rgb & 0xFF) / 255.0f;
                chw[idxR++] = r;
                chw[idxG++] = gch;
                chw[idxB++] = b;
            }
        }
        return chw;
    }

    private float parseMaxConfidence(OnnxValue value) throws OrtException {
        if (!(value instanceof OnnxTensor tensor)) {
            return 0f;
        }
        TensorInfo info = (TensorInfo) tensor.getInfo();
        long[] shape = info.getShape();
        float max = 0f;
        Object raw = tensor.getValue();
        if (raw instanceof float[][][] arr3) {
            for (float[][] a2 : arr3) {
                for (float[] row : a2) {
                    if (row.length > 4) {
                        max = Math.max(max, row[4]);
                    }
                }
            }
        } else if (raw instanceof float[][] arr2) {
            for (float[] row : arr2) {
                if (row.length > 4) {
                    max = Math.max(max, row[4]);
                }
            }
        } else if (raw instanceof float[] arr1) {
            for (float v : arr1) {
                max = Math.max(max, v);
            }
        } else if (shape.length == 0) {
            max = ((Number) raw).floatValue();
        }
        return max;
    }

    private float estimateMotionScore(String channelId, BufferedImage image) {
        int w = 64;
        int h = 36;
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = small.createGraphics();
        g.drawImage(image, 0, 0, w, h, null);
        g.dispose();

        float[] gray = new float[w * h];
        int idx = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                gray[idx++] = small.getRaster().getSample(x, y, 0);
            }
        }
        float[] prev = previousGray.put(channelId, gray);
        if (prev == null || prev.length != gray.length) {
            return 0.1f;
        }
        float diff = 0f;
        for (int i = 0; i < gray.length; i++) {
            diff += Math.abs(gray[i] - prev[i]);
        }
        return Math.min(1.0f, diff / (gray.length * 32f));
    }
}
