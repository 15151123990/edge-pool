package com.pool.edge.event.impl;

import com.pool.edge.common.model.Enums.AlertLevel;
import com.pool.edge.common.model.Enums.EventType;
import com.pool.edge.common.model.EventDecision;
import com.pool.edge.common.model.EventSignal;
import com.pool.edge.event.api.RuleEngine;
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
    private final Map<String, Integer> noHeadCounter = new ConcurrentHashMap<>();
    private final Map<String, Integer> struggleCounter = new ConcurrentHashMap<>();
    private final Map<String, Integer> staticCounter = new ConcurrentHashMap<>();
    private final Map<String, Integer> dangerStayCounter = new ConcurrentHashMap<>();

    @Override
    public Optional<EventDecision> evaluate(EventSignal s) {
        // 每个目标以 channelId + trackId 作为状态键
        String key = s.channelId() + ":" + s.trackId();

        // 规则1：长时没顶
        if (!s.headVisible()) {
            int n = noHeadCounter.merge(key, 1, Integer::sum);
            if (n >= 15) {
                return Optional.of(decision(EventType.LONG_SUBMERGE, AlertLevel.L3_EMERGENCY, s, "head invisible over multi-frame threshold"));
            }
        } else {
            noHeadCounter.remove(key);
        }

        // 规则2：垂直挣扎（位移低 + 动作幅度高）
        if (s.speed() < 0.05f && s.motionScore() > 0.7f) {
            int n = struggleCounter.merge(key, 1, Integer::sum);
            if (n >= 12) {
                return Optional.of(decision(EventType.VERTICAL_STRUGGLE, AlertLevel.L2_ALARM, s, "low displacement with high struggle score"));
            }
        } else {
            struggleCounter.remove(key);
        }

        // 规则3：异常静止（动作极低 + 头部不可见）
        if (s.motionScore() < 0.1f && !s.headVisible()) {
            int n = staticCounter.merge(key, 1, Integer::sum);
            if (n >= 20) {
                return Optional.of(decision(EventType.ABNORMAL_STATIC, AlertLevel.L2_ALARM, s, "abnormal static status in water"));
            }
        } else {
            staticCounter.remove(key);
        }

        // 规则4：危险区滞留
        if (s.inDangerZone()) {
            int n = dangerStayCounter.merge(key, 1, Integer::sum);
            if (n >= 30) {
                return Optional.of(decision(EventType.DANGER_ZONE_STAY, AlertLevel.L1_PRE_WARNING, s, "stay too long in danger zone"));
            }
        } else {
            dangerStayCounter.remove(key);
        }

        return Optional.empty();
    }

    /**
     * 构造事件决策对象。
     *
     * @param type 事件类型
     * @param level 告警等级
     * @param s 原始信号
     * @param reason 触发原因
     * @return 事件决策
     */
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
}
