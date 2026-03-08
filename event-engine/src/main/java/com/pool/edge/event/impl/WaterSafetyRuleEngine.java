package com.pool.edge.event.impl;

import com.pool.edge.common.model.Enums.AlertLevel;
import com.pool.edge.common.model.Enums.EventType;
import com.pool.edge.common.model.EventDecision;
import com.pool.edge.common.model.EventSignal;
import com.pool.edge.event.api.RuleEngine;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 防溺水规则引擎实现。
 * 基于多帧累计计数判断四类事件，降低单帧误报。
 */
@Component
public class WaterSafetyRuleEngine implements RuleEngine {
    private static final long STALE_KEY_TTL_MS = 60_000L;

    private final Map<String, CounterState> noHeadCounter = new ConcurrentHashMap<>();
    private final Map<String, CounterState> struggleCounter = new ConcurrentHashMap<>();
    private final Map<String, CounterState> staticCounter = new ConcurrentHashMap<>();
    private final Map<String, CounterState> dangerStayCounter = new ConcurrentHashMap<>();

    @Override
    public Optional<EventDecision> evaluate(EventSignal s) {
        String key = s.channelId() + ":" + s.trackId();
        long now = System.currentTimeMillis();

        if (!s.headVisible()) {
            int n = increment(noHeadCounter, key, now);
            if (n >= 15) {
                return Optional.of(decision(EventType.LONG_SUBMERGE, AlertLevel.L3_EMERGENCY, s, "head invisible over multi-frame threshold"));
            }
        } else {
            noHeadCounter.remove(key);
        }

        if (s.speed() < 0.05f && s.motionScore() > 0.7f) {
            int n = increment(struggleCounter, key, now);
            if (n >= 12) {
                return Optional.of(decision(EventType.VERTICAL_STRUGGLE, AlertLevel.L2_ALARM, s, "low displacement with high struggle score"));
            }
        } else {
            struggleCounter.remove(key);
        }

        if (s.motionScore() < 0.1f && !s.headVisible()) {
            int n = increment(staticCounter, key, now);
            if (n >= 20) {
                return Optional.of(decision(EventType.ABNORMAL_STATIC, AlertLevel.L2_ALARM, s, "abnormal static status in water"));
            }
        } else {
            staticCounter.remove(key);
        }

        if (s.inDangerZone()) {
            int n = increment(dangerStayCounter, key, now);
            if (n >= 30) {
                return Optional.of(decision(EventType.DANGER_ZONE_STAY, AlertLevel.L1_PRE_WARNING, s, "stay too long in danger zone"));
            }
        } else {
            dangerStayCounter.remove(key);
        }

        return Optional.empty();
    }

    @Scheduled(fixedRate = 60_000)
    public void cleanupStaleCounters() {
        long now = System.currentTimeMillis();
        cleanupStale(noHeadCounter, now);
        cleanupStale(struggleCounter, now);
        cleanupStale(staticCounter, now);
        cleanupStale(dangerStayCounter, now);
    }

    private int increment(Map<String, CounterState> map, String key, long now) {
        CounterState state = map.computeIfAbsent(key, k -> new CounterState(0, now));
        state.count++;
        state.lastUpdateMs = now;
        return state.count;
    }

    private void cleanupStale(Map<String, CounterState> map, long now) {
        map.entrySet().removeIf(entry ->
                now - entry.getValue().lastUpdateMs > STALE_KEY_TTL_MS
        );
    }

    private EventDecision decision(EventType type, AlertLevel level, EventSignal s, String reason) {
        long now = System.currentTimeMillis();
        return new EventDecision(
                UUID.randomUUID().toString(),
                type,
                level,
                s.channelId(),
                s.trackId(),
                now,
                now,
                reason
        );
    }

    private static final class CounterState {
        volatile int count;
        volatile long lastUpdateMs;

        CounterState(int count, long lastUpdateMs) {
            this.count = count;
            this.lastUpdateMs = lastUpdateMs;
        }
    }
}
