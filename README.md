# 边缘防溺水平台（Java 多模块）

面向泳池防溺水场景的边缘 AI 平台，当前已接入真实主链路：

`拉流(FFmpeg) -> 推理(ONNX Runtime) -> 规则引擎 -> 告警管道(截图/短片段上传)`

## 模块说明

- `edge-common`：通用模型、枚举、协议、签名工具。
- `stream-gateway`：通道管理与 FFmpeg 实时拉流。
- `inference-engine`：模型注册与 ONNX 推理。
- `event-engine`：四类防溺水事件规则判断。
- `alert-uploader`：告警附件生成与上传（支持阿里云 OSS）。
- `edge-agent`：边缘节点服务（主链路编排、设备运维接口、边云同步）。
- `control-plane`：控制面服务（心跳接收、任务分发、告警元数据入库接口）。

## 已实现的四类防溺水事件

- 长时没顶（`LONG_SUBMERGE`）
- 垂直挣扎（`VERTICAL_STRUGGLE`）
- 异常静止（`ABNORMAL_STILLNESS`）
- 危险区滞留（`DANGER_ZONE_STAY`）

规则控制支持：

- 多帧确认
- ROI 区域限制
- 告警分级（L1/L2/L3）
- 告警只上传截图/短片段，视频主体留边缘端

## 构建

```bash
mvn -DskipTests package
```

## 启动

```bash
mvn -pl edge-agent spring-boot:run
mvn -pl control-plane spring-boot:run
```

## 真实链路联调步骤

1. 注册模型（边缘本机 ONNX 路径）

```bash
curl -X POST http://localhost:8081/edge/models \
  -H "Content-Type: application/json" \
  -d '{
    "modelProfileId":"pool-person-det",
    "version":"v1",
    "uri":"/data/models/pool_person_v1.onnx",
    "checksum":""
  }'
```

2. 添加通道（RTSP + 模型绑定 + ROI）

```bash
curl -X POST http://localhost:8081/edge/channels \
  -H "Content-Type: application/json" \
  -d '{
    "channelId":"pool-001",
    "streamUrl":"rtsp://user:pass@192.168.1.10:554/Streaming/Channels/101",
    "active":false,
    "roiPolygons":["[[0.05,0.20],[0.95,0.20],[0.95,0.95],[0.05,0.95]]"],
    "modelProfileId":"pool-person-det",
    "modelVersion":"v1"
  }'
```

3. 启动通道

```bash
curl -X POST http://localhost:8081/edge/channels/pool-001/start
```

4. 查看通道配置

```bash
curl http://localhost:8081/edge/channels
```

5. 抓取通道快照（用于前端绘制 ROI）

```bash
curl -o pool-001.jpg http://localhost:8081/edge/channels/pool-001/snapshot
```

6. 更新通道 ROI

```bash
curl -X PUT http://localhost:8081/edge/channels/pool-001/roi \
  -H "Content-Type: application/json" \
  -d '{
    "roiPolygons":["[[0.08,0.18],[0.92,0.18],[0.92,0.96],[0.08,0.96]]"]
  }'
```

## 掉线与重连机制（当前实现）

- 每次调度拉取一帧，若 `grabImage()` 返回空帧，立即释放当前抓流器。
- 下一轮调度会自动重建 FFmpeg 抓流器，实现自动重连。
- 通道 `stop` 或删除时会主动 `release()`，避免资源泄漏。

建议在线上再加两个策略（下一版可直接实现）：

- 指数退避重连（1s/2s/4s...最大30s），避免断网时频繁重连打满 CPU。
- 连续失败熔断阈值（例如 30 次），上报设备告警并等待人工干预。

当前版本已实现以上两项，并提供运维查询接口：

- `GET /edge/stream-status`
- `GET /edge/channels/{channelId}/stream-status`

可调参数（`edge-agent/src/main/resources/application.yml`）：

- `edge.stream.reconnect.base-backoff-ms`（默认 `1000`）
- `edge.stream.reconnect.max-backoff-ms`（默认 `30000`）
- `edge.stream.reconnect.fuse-threshold`（默认 `30`）
- `edge.stream.reconnect.fuse-cooldown-ms`（默认 `30000`）

## MQTT 任务通道（可选）

默认关闭。开启后，控制面在 HTTP 入队任务时，也会通过 MQTT 推送：

- topic：`pool/edge/tasks/{deviceId}`
- 同一 `taskId` 去重，避免 MQTT 与 HTTP 双通道重复执行

配置：

1. `control-plane/src/main/resources/application.yml`
   - `cloud.mqtt.enabled: true`
   - `cloud.mqtt.broker-url: tcp://<broker-host>:1883`
2. `edge-agent/src/main/resources/application.yml`
   - `edge.mqtt.enabled: true`
   - `edge.mqtt.broker-url: tcp://<broker-host>:1883`

## 告警附件上传（阿里云 OSS）

边缘端先生成告警截图与短片段，再上传到 OSS，最后只把元数据上报控制面。

`edge-agent/src/main/resources/application.yml` 关键配置：

- `edge.alert.storage.type: oss`
- `edge.alert.storage.oss.endpoint`
- `edge.alert.storage.oss.bucket`
- `edge.alert.storage.oss.access-key-id`
- `edge.alert.storage.oss.access-key-secret`
- `edge.alert.storage.oss.public-base`
- `edge.alert.ffmpeg.enabled`
- `edge.alert.ffmpeg.bin`
- `edge.alert.ffmpeg.source`

## 端口说明（默认）

- `edge-agent`: `8081`
- `control-plane`: `8090`
