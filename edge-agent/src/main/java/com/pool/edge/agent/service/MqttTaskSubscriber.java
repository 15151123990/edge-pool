package com.pool.edge.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pool.edge.common.protocol.OpsTask;
import com.pool.edge.common.protocol.TaskResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MQTT 任务订阅器。
 * 订阅设备专属主题并执行下发任务。
 */
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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpsTaskService opsTaskService;
    private final EdgeCloudClient edgeCloudClient;
    private MqttClient client;

    /**
     * 构造订阅器。
     *
     * @param opsTaskService 运维任务执行服务
     * @param edgeCloudClient 控制面客户端（用于回执上报）
     */
    public MqttTaskSubscriber(OpsTaskService opsTaskService, EdgeCloudClient edgeCloudClient) {
        this.opsTaskService = opsTaskService;
        this.edgeCloudClient = edgeCloudClient;
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            return;
        }
        try {
            // 1) 初始化 MQTT 客户端并连接 broker
            String clientId = clientIdPrefix + "-" + deviceId + "-" + System.currentTimeMillis();
            client = new MqttClient(brokerUrl, clientId);
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    System.err.println("[MQTT] connection lost: " + cause.getMessage());
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    // 2) 消息反序列化为运维任务
                    OpsTask task = objectMapper.readValue(message.getPayload(), OpsTask.class);
                    // 3) 执行任务并上报回执
                    String msg = opsTaskService.execute(task);
                    edgeCloudClient.reportTaskResult(new TaskResult(
                            task.taskId(),
                            task.deviceId(),
                            "SUCCESS",
                            "[mqtt] " + msg,
                            System.currentTimeMillis()
                    ));
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // subscriber side no-op
                }
            });
            client.connect(options);
            // 4) 订阅当前设备专属 topic
            String topic = taskTopicPrefix + "/" + deviceId;
            client.subscribe(topic, qos);
        } catch (Exception e) {
            throw new IllegalStateException("mqtt subscriber init failed", e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (client == null) {
            return;
        }
        try {
            // 服务关闭时断开 MQTT 连接
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException ignored) {
        }
    }
}
