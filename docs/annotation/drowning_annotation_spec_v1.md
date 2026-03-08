# 防溺水四类事件标注规范 v1

## 1. 目标
用于泳池场景四类事件训练与评测：
- `LONG_SUBMERGE`（长时没顶）
- `VERTICAL_STRUGGLE`（垂直挣扎）
- `ABNORMAL_STILLNESS`（异常静止）
- `DANGER_ZONE_STAY`（危险区滞留）

## 2. 标注单位
采用“目标轨迹 + 时间段”联合标注：
- 空间：每帧 `bbox`（最小先做框）
- 时间：事件 `start_ms` / `end_ms`
- 身份：同一人跨帧保持同一个 `track_id`

## 3. 异常静止（ABNORMAL_STILLNESS）判定
必须同时满足：
1. 人在泳池 ROI 内（`roi_in=true`）
2. 连续 `>= T_still` 秒几乎无位移/无姿态变化
3. 非正常静止（如池边休息、教练示范、憋气训练）

建议初始阈值：
- `T_still = 10s`（可在 8~15s 微调）
- 退出条件：明显运动持续 `>=2s`

## 4. 四类事件建议阈值（起步版）
- `LONG_SUBMERGE`: 头部不可见持续 `>=8s`
- `VERTICAL_STRUGGLE`: 高运动+垂直姿态异常持续 `>=3s`
- `ABNORMAL_STILLNESS`: 低运动静止持续 `>=10s`
- `DANGER_ZONE_STAY`: 在危险区停留 `>=20s`

## 5. 负样本要求（必须标）
以下场景必须标为负样本（`NORMAL_*` 或 `no_alarm`）：
- 正常漂浮放松
- 低头憋气短暂停
- 池边抓扶手休息
- 教练演示动作

## 6. 最小字段
- `video_id`
- `channel_id`
- `track_id`
- `frame_ts_ms`
- `bbox_x1,bbox_y1,bbox_x2,bbox_y2`（归一化 0~1）
- `roi_in`
- `event_type`
- `event_start_ms,event_end_ms`
- `occlusion`
- `quality`
- `remark`

## 7. 质检建议
- 每段视频抽检 10% 帧
- 每类事件至少 100 段有效片段再进入首轮训练
- 夜间/反光/水花样本占比不低于 30%
