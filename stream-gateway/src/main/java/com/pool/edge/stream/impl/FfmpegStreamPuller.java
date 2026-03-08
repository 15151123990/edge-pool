package com.pool.edge.stream.impl;

import com.pool.edge.common.model.ChannelConfig;
import com.pool.edge.common.model.FramePacket;
import com.pool.edge.stream.api.StreamHealthStatus;
import com.pool.edge.stream.api.StreamPuller;
import jakarta.annotation.PreDestroy;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 FFmpeg 的真实拉流实现。
 * 支持 RTSP 流的连接、取帧和断线重连。
 */
@Component
public class FfmpegStreamPuller implements StreamPuller {
    static {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
    }

    private final Map<String, GrabberHolder> holderByChannel = new ConcurrentHashMap<>();
    private final Map<String, MutableHealthState> healthByChannel = new ConcurrentHashMap<>();

    @Value("${edge.stream.reconnect.base-backoff-ms:1000}")
    private long baseBackoffMs;

    @Value("${edge.stream.reconnect.max-backoff-ms:30000}")
    private long maxBackoffMs;

    @Value("${edge.stream.reconnect.fuse-threshold:30}")
    private int fuseThreshold;

    @Value("${edge.stream.reconnect.fuse-cooldown-ms:30000}")
    private long fuseCooldownMs;

    @Override
    public Optional<FramePacket> pull(ChannelConfig channel) {
        long now = System.currentTimeMillis();
        String channelId = channel.channelId();
        MutableHealthState state = healthByChannel.computeIfAbsent(channelId, id -> new MutableHealthState(channelId));
        if (now < state.nextRetryAtMs) {
            return Optional.empty();
        }

        try {
            GrabberHolder holder = holderByChannel.get(channelId);
            if (holder == null) {
                holder = openGrabber(channel);
                holderByChannel.put(channelId, holder);
                state.totalReconnects++;
            }
            Frame frame = holder.grabber.grabImage();
            if (frame == null) {
                markFailure(state, now, "grabImage empty frame");
                release(channelId);
                return Optional.empty();
            }
            BufferedImage image = holder.converter.getBufferedImage(frame);
            if (image == null) {
                markFailure(state, now, "convert frame to image failed");
                return Optional.empty();
            }
            long idx = ++holder.frameIndex;
            markSuccess(state, now);
            return Optional.of(new FramePacket(channelId, idx, now, image));
        } catch (Exception e) {
            String reason = e.getMessage() == null ? "stream exception" : e.getMessage();
            markFailure(state, now, reason);
            release(channelId);
            return Optional.empty();
        }
    }

    @Override
    public void release(String channelId) {
        GrabberHolder holder = holderByChannel.remove(channelId);
        if (holder == null) {
            return;
        }
        try {
            holder.grabber.stop();
        } catch (Exception ignored) {
        }
        try {
            holder.grabber.release();
        } catch (Exception ignored) {
        }
        holder.converter.close();

        MutableHealthState state = healthByChannel.get(channelId);
        if (state != null) {
            state.connected = false;
        }
    }

    @Override
    public StreamHealthStatus status(String channelId) {
        MutableHealthState state = healthByChannel.computeIfAbsent(channelId, MutableHealthState::new);
        return state.snapshot();
    }

    @Override
    public Map<String, StreamHealthStatus> allStatus() {
        Map<String, StreamHealthStatus> result = new HashMap<>();
        for (Map.Entry<String, MutableHealthState> e : healthByChannel.entrySet()) {
            result.put(e.getKey(), e.getValue().snapshot());
        }
        return result;
    }

    @PreDestroy
    public void destroy() {
        for (String channelId : holderByChannel.keySet()) {
            release(channelId);
        }
    }

    private GrabberHolder openGrabber(ChannelConfig channel) {
        try {
            FFmpegFrameGrabber g = new FFmpegFrameGrabber(channel.streamUrl());
            g.setOption("rtsp_transport", "tcp");
            g.setOption("stimeout", "5000000");
            g.setOption("rw_timeout", "5000000");
            g.setOption("fflags", "nobuffer");
            g.setOption("flags", "low_delay");
            g.start();
            return new GrabberHolder(g, new Java2DFrameConverter());
        } catch (Exception e) {
            throw new IllegalStateException("open stream failed: " + channel.channelId(), e);
        }
    }

    private void markSuccess(MutableHealthState state, long now) {
        state.connected = true;
        state.circuitOpen = false;
        state.consecutiveFailures = 0;
        state.lastSuccessAtMs = now;
        state.nextRetryAtMs = 0;
        state.lastError = "";
    }

    private void markFailure(MutableHealthState state, long now, String reason) {
        state.connected = false;
        state.consecutiveFailures++;
        state.totalFailures++;
        state.lastFailureAtMs = now;
        state.lastError = reason;

        if (state.consecutiveFailures >= fuseThreshold) {
            state.circuitOpen = true;
        }

        long exponent = Math.max(0, state.consecutiveFailures - 1);
        long backoff = baseBackoffMs;
        for (int i = 0; i < exponent; i++) {
            if (backoff >= maxBackoffMs / 2) {
                backoff = maxBackoffMs;
                break;
            }
            backoff *= 2;
        }
        backoff = Math.min(backoff, maxBackoffMs);
        if (state.circuitOpen) {
            backoff = Math.max(backoff, fuseCooldownMs);
        }
        state.nextRetryAtMs = now + backoff;
    }

    private static final class MutableHealthState {
        private final String channelId;
        private boolean connected = false;
        private boolean circuitOpen = false;
        private int consecutiveFailures = 0;
        private long totalFailures = 0;
        private long totalReconnects = 0;
        private long lastSuccessAtMs = 0;
        private long lastFailureAtMs = 0;
        private long nextRetryAtMs = 0;
        private String lastError = "";

        private MutableHealthState(String channelId) {
            this.channelId = channelId;
        }

        private StreamHealthStatus snapshot() {
            return new StreamHealthStatus(
                    channelId,
                    connected,
                    circuitOpen,
                    consecutiveFailures,
                    totalFailures,
                    totalReconnects,
                    lastSuccessAtMs,
                    lastFailureAtMs,
                    nextRetryAtMs,
                    lastError
            );
        }
    }

    private static final class GrabberHolder {
        private final FFmpegFrameGrabber grabber;
        private final Java2DFrameConverter converter;
        private long frameIndex = 0;

        private GrabberHolder(FFmpegFrameGrabber grabber, Java2DFrameConverter converter) {
            this.grabber = grabber;
            this.converter = converter;
        }
    }
}
