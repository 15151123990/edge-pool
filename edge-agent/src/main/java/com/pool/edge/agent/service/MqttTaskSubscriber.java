package com.pool.edge.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pool.edge.common.protocol.OpsTask;
import com.pool.edge.common.protocol.TaskResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MQTT 任务订阅器。
 * 订阅设备专属主题并执行下发任务。
 */
@Slf4j
@Component
public class MqttTaskSubscriber {

    @Value("${edge.mqtt.enabled:false}")
    private boolean enabled;

    @Value("${edge.mqtt.broker-url:tcp://127.0.0.1:1883}")
    private String brokerUrl;

    @Value("${edge.mqtt.client-id-prefix:edge-agent}")
    private String clientIdPrefix;

    @Value("${edge.mqtt.qos:1}")
    private int qos;

    @Value("${edge.mqtt.task-topic-prefix:pool/edge/tasks}")
    private String taskTopicPrefix;

    @Value("${edge.device.id:edge-001}")
    private String deviceId;

    @Value("${edge.cloud.base-url:http://127.0.0.1:19090}")
    private String cloudBaseUrl;

    @Value("${edge.cloud.secret:dev-secret}")
    private String secret;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpsTaskService opsTaskService;
    private final EdgeCloudClient edgeCloudClient;
    private MqttClient client;

    public MqttTaskSubscriber(OpsTaskService opsTaskService, EdgeCloudClient edgeCloudClient) {
        this.opsTaskService = opsTaskService;
        this.edgeCloudClient = edgeCloudClient;
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("[MQTT] subscriber disabled");
            return;
        }
        try {
            String clientId = clientIdPrefix + "-" + deviceId + "-" + System.currentTimeMillis();
            client = new MqttClient(brokerUrl, clientId);
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    log.warn("[MQTT] connection lost: {}", cause.getMessage());
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    handleMessage(message);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            client.connect(options);
            String topic = taskTopicPrefix + "/" + deviceId;
            client.subscribe(topic, qos);
            log.info("[MQTT] subscriber initialized, topic={}", topic);
        } catch (Exception e) {
            log.error("[MQTT] subscriber init failed: {}", e.getMessage(), e);
            throw new IllegalStateException("mqtt subscriber init failed", e);
        }
    }

    private void handleMessage(MqttMessage message) {
        try {
            OpsTask task = objectMapper.readValue(message.getPayload(), OpsTask.class);
            log.info("[MQTT] received task: taskId={}, type={}", task.taskId(), task.type());

            String msg = opsTaskService.execute(task);

            TaskResult result = new TaskResult(
                    task.taskId(),
                    task.deviceId(),
                    "SUCCESS",
                    "[mqtt] " + msg,
                    System.currentTimeMillis()
            );
            edgeCloudClient.reportTaskResult(result);
            log.info("[MQTT] task completed: taskId={}", task.taskId());
        } catch (Exception e) {
            log.error("[MQTT] message handling failed: {}", e.getMessage(), e);
            tryReportFailure(message, e);
        }
    }

    private void tryReportFailure(MqttMessage message, Exception error) {
        try {
            String payload = new String(message.getPayload());
            String taskId = "unknown";
            if (payload.contains("taskId")) {
                int start = payload.indexOf("\"taskId\":\"") + 10;
                int end = payload.indexOf("\"", start);
                if (start > 9 && end > start) {
                    taskId = payload.substring(start, end);
                }
            }
            TaskResult result = new TaskResult(
                    taskId,
                    deviceId,
                    "FAILED",
                    "[mqtt] processing error: " + error.getMessage(),
                    System.currentTimeMillis()
            );
            edgeCloudClient.reportTaskResult(result);
        } catch (Exception e) {
            log.error("[MQTT] failed to report error: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect();
                log.info("[MQTT] disconnected");
            }
            client.close();
        } catch (MqttException e) {
            log.warn("[MQTT] close error: {}", e.getMessage());
        }
    }
}
