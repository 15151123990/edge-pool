# 边缘盒子部署工具（中文说明）

这套脚本用于 RK3568/RK3588 等 ARM64 边缘盒子的批量部署与运维，支持两种方式：

- 方式A：直接部署 `Java jar + systemd`
- 方式B：部署 Docker 容器镜像

## 功能概览

- 自动安装 Java 17（缺失时）
- 初始化 `/opt/pool-ai` 目录结构
- 下发 `app.jar` 与配置文件
- 注册并启动 `systemd` 服务
- 基于清单文件批量部署多台盒子
- 批量执行 `status/start/stop/restart/logs/pull-logs`
- Docker 批量发布、状态检查、日志查看、回滚

## 目录结构

```text
scripts/edge-deploy/
  deploy.sh                # 本机执行：jar + systemd 部署
  deploy-docker.sh         # 本机执行：Docker 批量部署
  remote_install.sh        # 远端执行：盒子初始化和 systemd 安装
  inventory.example.csv    # 设备清单模板
```

## 前置条件

- 本机具备：`bash`、`ssh`、`scp`
- 可通过 SSH 访问边缘盒子
- 远端账号有 `sudo` 权限（建议免密 sudo）
- 已准备好应用产物（`app.jar` 或 Docker 镜像）

## 设备清单格式

CSV 首行必须是表头：

```csv
name,host,port,user
pool-a,192.168.2.20,22,root
pool-b,192.168.2.21,22,root
```

## 快速开始（jar + systemd）

1. 准备文件：
   - `app.jar`
   - `application-prod.yml`

2. 复制并编辑清单：

```bash
cp scripts/edge-deploy/inventory.example.csv ./inventory.csv
```

3. 批量部署：

```bash
bash scripts/edge-deploy/deploy.sh deploy \
  --inventory ./inventory.csv \
  --jar /绝对路径/app.jar \
  --config /绝对路径/application-prod.yml \
  --service pool-ai \
  --ssh-timeout 8
```

4. 查看状态：

```bash
bash scripts/edge-deploy/deploy.sh status --inventory ./inventory.csv --service pool-ai
```

## 常用运维命令（jar + systemd）

```bash
# 启动 / 停止 / 重启
bash scripts/edge-deploy/deploy.sh start --inventory ./inventory.csv --service pool-ai
bash scripts/edge-deploy/deploy.sh stop --inventory ./inventory.csv --service pool-ai
bash scripts/edge-deploy/deploy.sh restart --inventory ./inventory.csv --service pool-ai

# 查看日志（每台机器输出最近 N 行）
bash scripts/edge-deploy/deploy.sh logs --inventory ./inventory.csv --service pool-ai --lines 80

# 拉取远端日志到本地目录
bash scripts/edge-deploy/deploy.sh pull-logs --inventory ./inventory.csv --service pool-ai --out-dir ./logs
```

## Docker 批量部署

```bash
# 批量发布新镜像
bash scripts/edge-deploy/deploy-docker.sh deploy \
  --inventory ./inventory.csv \
  --image your-registry/pool-ai:1.0.0 \
  --service pool-ai \
  --ssh-option "-o StrictHostKeyChecking=accept-new"

# 查看容器状态
bash scripts/edge-deploy/deploy-docker.sh status --inventory ./inventory.csv --service pool-ai

# 查看容器日志
bash scripts/edge-deploy/deploy-docker.sh logs --inventory ./inventory.csv --service pool-ai --lines 120

# 回滚到上一次部署前的镜像
bash scripts/edge-deploy/deploy-docker.sh rollback --inventory ./inventory.csv --service pool-ai
```

## 参数说明（重点）

- `--inventory`：设备清单 CSV 路径
- `--service`：服务名/容器名，默认 `pool-ai`
- `--ssh-option`：额外 SSH 参数，可重复传入
- `--java-opts`：JVM 参数，默认 `-Xms512m -Xmx1024m`
- `--health-url`：Docker 发布后的健康检查地址
- `--health-wait`：健康检查最长等待秒数

## 使用建议

- 首次连接新盒子，建议加：
  - `--ssh-option "-o StrictHostKeyChecking=accept-new"`
- 如果远端账号不是 root，脚本会通过 `sudo` 执行系统级操作。
- 建议先用 1 台盒子试发布，再批量执行全量部署。
