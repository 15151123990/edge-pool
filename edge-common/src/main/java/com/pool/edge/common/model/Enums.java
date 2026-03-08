package com.pool.edge.common.model;

/**
 * 平台通用枚举定义。
 * 集中维护事件类型、告警级别、运维任务类型。
 */
public final class Enums {
    private Enums() {}

    /** 防溺水事件类型。 */
    public enum EventType {
        LONG_SUBMERGE,
        VERTICAL_STRUGGLE,
        ABNORMAL_STATIC,
        DANGER_ZONE_STAY
    }

    /** 告警等级。 */
    public enum AlertLevel {
        L1_PRE_WARNING,
        L2_ALARM,
        L3_EMERGENCY
    }

    /** 运维任务类型。 */
    public enum OpsType {
        RESTART_SERVICE,
        RESTART_DEVICE,
        APPLY_CONFIG,
        PUBLISH_MODEL,
        ROLLBACK_MODEL
    }
}
