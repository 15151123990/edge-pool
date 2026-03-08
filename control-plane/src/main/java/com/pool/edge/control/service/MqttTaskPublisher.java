package com.pool.edge.control.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pool.edge.common.protocol.OpsTask;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * MQTT 任务发布器。
 * 控制面在任务入队后可同时推送到设备订阅主题。
 */
@Service
public class MqttTaskPublisher {
    @Value("${cloud.mqtt.enabled:false}")
    private boolean enabled;

    @Value("${cloud.mqtt.broker-url:tcp://127.0.0.1:1883}")
    private String brokerUrl;

    @Value("${cloud.mqtt.client-id:control-plane}")
    private String clientId;

    @Value("${cloud.mqtt.qos:1}")
    private int qos;

    @Value("${cloud.mqtt.task-topic-prefix:pool/edge/tasks}")
    private String taskTopicPrefix;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MqttClient client;

    @PostConstruct
    public void init() {
        if (!enabled) {
            return;
        }
        try {
            // 1) 创建 MQTT 客户端并启用自动重连
            client = new MqttClient(brokerUrl, clientId + "-" + System.currentTimeMillis());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            client.connect(options);
        } catch (Exception e) {
            throw new IllegalStateException("mqtt publisher init failed", e);
        }
    }

    /**
     * 发布运维任务到 MQTT。
     *
     * @param task 运维任务
     */
    public void publishTask(OpsTask task) {
        if (!enabled || client == null || !client.isConnected()) {
            return;
        }
        try {
            // 2) 设备专属 topic：pool/edge/tasks/{deviceId}
            String topic = taskTopicPrefix + "/" + task.deviceId();
            byte[] payload = objectMapper.writeValueAsBytes(task);
            MqttMessage message = new MqttMessage(payload);
            message.setQos(qos);
            client.publish(topic, message);
        } catch (JsonProcessingException | MqttException e) {
            throw new IllegalStateException("mqtt publish task failed", e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (client == null) {
            return;
        }
        try {
            // 3) 服务退出时优雅断开
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException ignored) {
        }
    }
}
