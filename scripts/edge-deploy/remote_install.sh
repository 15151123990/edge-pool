#!/usr/bin/env bash
set -euo pipefail

SERVICE_NAME="${SERVICE_NAME:-pool-ai}"
APP_DIR="${APP_DIR:-/opt/pool-ai}"
APP_PATH="${APP_PATH:-$APP_DIR/app/app.jar}"
CONFIG_PATH="${CONFIG_PATH:-$APP_DIR/config/application-prod.yml}"
LOG_DIR="${LOG_DIR:-$APP_DIR/logs}"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx1024m}"
RUN_USER="${RUN_USER:-$(id -un)}"

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing command: $1" >&2
    exit 1
  }
}

install_java_if_needed() {
  if command -v java >/dev/null 2>&1; then
    if java -version 2>&1 | head -n1 | grep -q '"17\|18\|19\|20\|21'; then
      echo "[INFO] Java already available"
      return
    fi
  fi

  echo "[INFO] Installing Java 17 runtime..."
  if command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update -y
    sudo apt-get install -y openjdk-17-jre-headless
  elif command -v dnf >/dev/null 2>&1; then
    sudo dnf install -y java-17-openjdk-headless
  elif command -v yum >/dev/null 2>&1; then
    sudo yum install -y java-17-openjdk-headless
  else
    echo "[ERROR] Unsupported package manager; install Java 17 manually." >&2
    exit 1
  fi
}

prepare_dirs() {
  sudo mkdir -p "$APP_DIR/app" "$APP_DIR/config" "$LOG_DIR"
  sudo chown -R "$RUN_USER":"$RUN_USER" "$APP_DIR"
}

write_unit_file() {
  local unit_path="/etc/systemd/system/${SERVICE_NAME}.service"
  local java_bin
  java_bin="$(command -v java)"

  sudo tee "$unit_path" >/dev/null <<EOF
[Unit]
Description=Pool AI Edge Service (${SERVICE_NAME})
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=${APP_DIR}
ExecStart=${java_bin} ${JAVA_OPTS} -jar ${APP_PATH} --spring.config.location=${CONFIG_PATH}
Restart=always
RestartSec=3
User=${RUN_USER}
StandardOutput=append:${LOG_DIR}/stdout.log
StandardError=append:${LOG_DIR}/stderr.log
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF
}

enable_and_restart() {
  sudo systemctl daemon-reload
  sudo systemctl enable "${SERVICE_NAME}.service"
  sudo systemctl restart "${SERVICE_NAME}.service"
  sudo systemctl --no-pager --full status "${SERVICE_NAME}.service" | sed -n '1,20p'
}

main() {
  need_cmd sudo
  need_cmd systemctl
  need_cmd sed

  if [[ ! -f "$APP_PATH" ]]; then
    echo "[ERROR] app jar missing: $APP_PATH" >&2
    exit 1
  fi
  if [[ ! -f "$CONFIG_PATH" ]]; then
    echo "[ERROR] config missing: $CONFIG_PATH" >&2
    exit 1
  fi

  install_java_if_needed
  prepare_dirs
  write_unit_file
  enable_and_restart
}

main "$@"
