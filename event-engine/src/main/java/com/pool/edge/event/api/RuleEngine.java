package com.pool.edge.event.api;

import com.pool.edge.common.model.EventDecision;
import com.pool.edge.common.model.EventSignal;

import java.util.Optional;

/**
 * 规则引擎接口。
 * 输入推理信号，输出可选的事件决策。
 */
public interface RuleEngine {
    /** 执行规则评估。 */
    Optional<EventDecision> evaluate(EventSignal signal);
}
