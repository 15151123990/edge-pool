package com.pool.edge.alert.api;

import com.pool.edge.common.model.EventDecision;

/**
 * 告警管道接口。
 * 负责将规则引擎产出的事件决策落地为实际告警动作。
 */
public interface AlertPipeline {
    /** 处理单条事件决策。 */
    void handle(EventDecision decision);
}
