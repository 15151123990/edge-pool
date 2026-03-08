package com.pool.edge.agent.service;

import com.pool.edge.common.model.Enums.OpsType;
import com.pool.edge.common.protocol.OpsTask;
import com.pool.edge.stream.api.ChannelManager;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运维任务执行服务。
 * 负责在边缘端执行下发任务，并进行任务去重。
 */
@Service
public class OpsTaskService {
    private final ChannelManager channelManager;
    private final Set<String> executedTaskIds = ConcurrentHashMap.newKeySet();

    public OpsTaskService(ChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    /**
     * 执行运维任务。
     *
     * @param task 运维任务
     * @return 执行结果描述
     */
    public String execute(OpsTask task) {
        if (task.taskId() != null && !task.taskId().isBlank()) {
            boolean first = executedTaskIds.add(task.taskId());
            if (!first) {
                return "task ignored: duplicated taskId";
            }
        }
        if (task.type() == OpsType.RESTART_SERVICE) {
            return "service restart accepted";
        }
        if (task.type() == OpsType.RESTART_DEVICE) {
            return "device restart accepted";
        }
        if (task.type() == OpsType.APPLY_CONFIG) {
            return "config apply accepted";
        }
        if (task.type() == OpsType.PUBLISH_MODEL) {
            return "model publish accepted";
        }
        if (task.type() == OpsType.ROLLBACK_MODEL) {
            return "model rollback accepted";
        }
        return "unsupported";
    }
}
