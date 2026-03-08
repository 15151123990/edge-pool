#!/usr/bin/env bash
set -euo pipefail

ACTION="${1:-}"
if [[ "${ACTION:-}" == "-h" || "${ACTION:-}" == "--help" || -z "${ACTION:-}" ]]; then
  ACTION=""
else
  shift || true
fi

INVENTORY=""
JAR_PATH=""
CONFIG_PATH=""
SERVICE_NAME="pool-ai"
SSH_TIMEOUT=8
LINES=80
OUT_DIR="./logs"
JAVA_OPTS="-Xms512m -Xmx1024m"
SSH_EXTRA_OPTS=()

usage() {
  cat <<EOF
Usage:
  $0 deploy --inventory FILE --jar FILE --config FILE [--service NAME] [--java-opts "..."] [--ssh-timeout N]
  $0 status --inventory FILE [--service NAME]
  $0 start|stop|restart --inventory FILE [--service NAME]
  $0 logs --inventory FILE [--service NAME] [--lines N]
  $0 pull-logs --inventory FILE [--service NAME] [--out-dir DIR]

Options:
  --inventory FILE   CSV file: name,host,port,user
  --jar FILE         Local app jar path (required for deploy)
  --config FILE      Local Spring config path (required for deploy)
  --service NAME     systemd service name (default: pool-ai)
  --java-opts OPTS   Java options for service (default: -Xms512m -Xmx1024m)
  --ssh-timeout N    SSH connect timeout seconds (default: 8)
  --ssh-option OPT   Extra SSH option, repeatable
  --lines N          Log lines for logs action (default: 80)
  --out-dir DIR      Local output directory for pull-logs (default: ./logs)
EOF
}

require_cmds() {
  for c in bash ssh scp awk sed mktemp; do
    command -v "$c" >/dev/null 2>&1 || {
      echo "Missing command: $c" >&2
      exit 1
    }
  done
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --inventory) INVENTORY="$2"; shift 2 ;;
      --jar) JAR_PATH="$2"; shift 2 ;;
      --config) CONFIG_PATH="$2"; shift 2 ;;
      --service) SERVICE_NAME="$2"; shift 2 ;;
      --java-opts) JAVA_OPTS="$2"; shift 2 ;;
      --ssh-timeout) SSH_TIMEOUT="$2"; shift 2 ;;
      --ssh-option) SSH_EXTRA_OPTS+=("$2"); shift 2 ;;
      --lines) LINES="$2"; shift 2 ;;
      --out-dir) OUT_DIR="$2"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) echo "Unknown argument: $1" >&2; usage; exit 1 ;;
    esac
  done
}

validate_common() {
  [[ -f "$INVENTORY" ]] || { echo "Inventory not found: $INVENTORY" >&2; exit 1; }
}

validate_deploy() {
  [[ -f "$JAR_PATH" ]] || { echo "Jar not found: $JAR_PATH" >&2; exit 1; }
  [[ -f "$CONFIG_PATH" ]] || { echo "Config not found: $CONFIG_PATH" >&2; exit 1; }
}

ssh_base_opts=()
setup_ssh_opts() {
  ssh_base_opts=(-o ConnectTimeout="$SSH_TIMEOUT" -o BatchMode=yes)
  for opt in "${SSH_EXTRA_OPTS[@]}"; do
    ssh_base_opts+=("$opt")
  done
}

run_ssh() {
  local user="$1" host="$2" port="$3" cmd="$4"
  ssh "${ssh_base_opts[@]}" -p "$port" "$user@$host" "$cmd"
}

run_scp() {
  local src="$1" user="$2" host="$3" port="$4" dst="$5"
  scp "${ssh_base_opts[@]}" -P "$port" "$src" "$user@$host:$dst"
}

iterate_inventory() {
  awk -F',' 'NR>1 && NF>=4 {gsub(/\r/,"",$0); print $1 "|" $2 "|" $3 "|" $4}' "$INVENTORY"
}

action_deploy_host() {
  local name="$1" host="$2" port="$3" user="$4"
  echo "[$name] Deploying to $user@$host:$port"

  local tmp_dir="/tmp/pool-ai-deploy"
  run_ssh "$user" "$host" "$port" "mkdir -p $tmp_dir"

  run_scp "$JAR_PATH" "$user" "$host" "$port" "$tmp_dir/app.jar"
  run_scp "$CONFIG_PATH" "$user" "$host" "$port" "$tmp_dir/application-prod.yml"

  local local_remote_script
  local_remote_script="$(cd "$(dirname "$0")" && pwd)/remote_install.sh"
  run_scp "$local_remote_script" "$user" "$host" "$port" "$tmp_dir/remote_install.sh"

  local remote_cmd="
set -euo pipefail
sudo mkdir -p /opt/pool-ai/app /opt/pool-ai/config /opt/pool-ai/logs
sudo cp -f $tmp_dir/app.jar /opt/pool-ai/app/app.jar
sudo cp -f $tmp_dir/application-prod.yml /opt/pool-ai/config/application-prod.yml
sudo chown -R $user:$user /opt/pool-ai
chmod +x $tmp_dir/remote_install.sh
SERVICE_NAME='$SERVICE_NAME' APP_DIR='/opt/pool-ai' APP_PATH='/opt/pool-ai/app/app.jar' CONFIG_PATH='/opt/pool-ai/config/application-prod.yml' LOG_DIR='/opt/pool-ai/logs' JAVA_OPTS='$JAVA_OPTS' RUN_USER='$user' bash $tmp_dir/remote_install.sh
"
  run_ssh "$user" "$host" "$port" "$remote_cmd"
}

action_service_host() {
  local name="$1" host="$2" port="$3" user="$4" op="$5"
  echo "[$name] $op on $user@$host:$port"
  run_ssh "$user" "$host" "$port" "sudo systemctl $op ${SERVICE_NAME}.service"
}

action_status_host() {
  local name="$1" host="$2" port="$3" user="$4"
  echo "[$name] status on $user@$host:$port"
  run_ssh "$user" "$host" "$port" "sudo systemctl --no-pager --full status ${SERVICE_NAME}.service | sed -n '1,20p'"
}

action_logs_host() {
  local name="$1" host="$2" port="$3" user="$4"
  echo "[$name] logs on $user@$host:$port"
  run_ssh "$user" "$host" "$port" "sudo journalctl -u ${SERVICE_NAME}.service -n ${LINES} --no-pager"
}

action_pull_logs_host() {
  local name="$1" host="$2" port="$3" user="$4"
  mkdir -p "$OUT_DIR/$name"
  echo "[$name] pulling logs to $OUT_DIR/$name"
  run_scp "$user@$host:/opt/pool-ai/logs/stdout.log" "$user" "$host" "$port" "/dev/null"
}

run_batch() {
  local failed=()
  local total=0
  while IFS='|' read -r name host port user; do
    [[ -n "${name:-}" ]] || continue
    total=$((total + 1))
    if [[ "$ACTION" == "deploy" ]]; then
      if ! action_deploy_host "$name" "$host" "$port" "$user"; then
        failed+=("$name($host)")
      fi
    elif [[ "$ACTION" == "status" ]]; then
      if ! action_status_host "$name" "$host" "$port" "$user"; then
        failed+=("$name($host)")
      fi
    elif [[ "$ACTION" == "logs" ]]; then
      if ! action_logs_host "$name" "$host" "$port" "$user"; then
        failed+=("$name($host)")
      fi
    elif [[ "$ACTION" == "pull-logs" ]]; then
      mkdir -p "$OUT_DIR/$name"
      if ! scp "${ssh_base_opts[@]}" -P "$port" "$user@$host:/opt/pool-ai/logs/stdout.log" "$OUT_DIR/$name/stdout.log"; then
        failed+=("$name($host)")
      fi
      if ! scp "${ssh_base_opts[@]}" -P "$port" "$user@$host:/opt/pool-ai/logs/stderr.log" "$OUT_DIR/$name/stderr.log"; then
        failed+=("$name($host)")
      fi
    else
      if ! action_service_host "$name" "$host" "$port" "$user" "$ACTION"; then
        failed+=("$name($host)")
      fi
    fi
  done < <(iterate_inventory)

  echo "------"
  echo "Total hosts: $total"
  if [[ ${#failed[@]} -gt 0 ]]; then
    echo "Failed: ${#failed[@]}"
    printf '  - %s\n' "${failed[@]}"
    exit 2
  fi
  echo "All hosts completed."
}

main() {
  require_cmds
  if [[ -z "$ACTION" ]]; then
    usage
    exit 0
  fi
  parse_args "$@"
  validate_common
  setup_ssh_opts

  case "$ACTION" in
    deploy) validate_deploy ;;
    status|start|stop|restart|logs|pull-logs) ;;
    *) echo "Unsupported action: $ACTION" >&2; usage; exit 1 ;;
  esac

  run_batch
}

main "$@"
