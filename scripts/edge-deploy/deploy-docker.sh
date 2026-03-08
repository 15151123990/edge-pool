#!/usr/bin/env bash
set -euo pipefail

ACTION="${1:-}"
if [[ -z "${ACTION}" || "${ACTION}" == "-h" || "${ACTION}" == "--help" ]]; then
  ACTION=""
else
  shift || true
fi

INVENTORY=""
IMAGE=""
SERVICE_NAME="pool-ai"
SSH_TIMEOUT=8
LINES=120
HEALTH_URL="http://127.0.0.1:8080/actuator/health"
HEALTH_WAIT=40
HEALTH_INTERVAL=2
SSH_EXTRA_OPTS=()
ENV_FILE=""
REMOTE_CONFIG_DIR="/opt/pool-ai/config"
REMOTE_LOG_DIR="/opt/pool-ai/logs"
CONTAINER_PORTS=""

usage() {
  cat <<EOF
Usage:
  $0 deploy --inventory FILE --image IMAGE [options]
  $0 rollback --inventory FILE [options]
  $0 status --inventory FILE [options]
  $0 logs --inventory FILE [options]
  $0 restart --inventory FILE [options]

Required:
  --inventory FILE        CSV: name,host,port,user

Deploy options:
  --image IMAGE           Docker image tag, e.g. registry/pool-ai:1.0.0
  --env-file FILE         Local env file to upload to /opt/pool-ai/config/.env (optional)
  --ports "PUBLISHES"     Extra port mappings, e.g. "8080:8080,9090:9090" (optional)

Common options:
  --service NAME          Container name (default: pool-ai)
  --ssh-timeout N         SSH connect timeout seconds (default: 8)
  --ssh-option OPT        Extra SSH option, repeatable
  --health-url URL        Health endpoint for deploy verify (default: http://127.0.0.1:8080/actuator/health)
  --health-wait SEC       Max wait seconds for health check (default: 40)
  --health-interval SEC   Interval seconds for health check (default: 2)
  --lines N               Lines for logs action (default: 120)
EOF
}

require_cmds() {
  for c in ssh scp awk sed mkdir mktemp; do
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
      --image) IMAGE="$2"; shift 2 ;;
      --service) SERVICE_NAME="$2"; shift 2 ;;
      --ssh-timeout) SSH_TIMEOUT="$2"; shift 2 ;;
      --ssh-option) SSH_EXTRA_OPTS+=("$2"); shift 2 ;;
      --health-url) HEALTH_URL="$2"; shift 2 ;;
      --health-wait) HEALTH_WAIT="$2"; shift 2 ;;
      --health-interval) HEALTH_INTERVAL="$2"; shift 2 ;;
      --lines) LINES="$2"; shift 2 ;;
      --env-file) ENV_FILE="$2"; shift 2 ;;
      --ports) CONTAINER_PORTS="$2"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) echo "Unknown argument: $1" >&2; usage; exit 1 ;;
    esac
  done
}

validate() {
  [[ -f "$INVENTORY" ]] || { echo "Inventory not found: $INVENTORY" >&2; exit 1; }
  if [[ "$ACTION" == "deploy" ]]; then
    [[ -n "$IMAGE" ]] || { echo "--image is required for deploy" >&2; exit 1; }
    if [[ -n "$ENV_FILE" ]]; then
      [[ -f "$ENV_FILE" ]] || { echo "Env file not found: $ENV_FILE" >&2; exit 1; }
    fi
  fi
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

build_ports_args() {
  local args=""
  if [[ -n "$CONTAINER_PORTS" ]]; then
    IFS=',' read -r -a mappings <<< "$CONTAINER_PORTS"
    for m in "${mappings[@]}"; do
      m="$(echo "$m" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
      [[ -n "$m" ]] && args="$args -p $m"
    done
  fi
  echo "$args"
}

deploy_host() {
  local name="$1" host="$2" port="$3" user="$4"
  local ports_args
  ports_args="$(build_ports_args)"
  echo "[$name] Deploying image $IMAGE"

  local cmd="
set -euo pipefail
command -v docker >/dev/null 2>&1 || { echo 'docker not found'; exit 1; }
sudo mkdir -p '$REMOTE_CONFIG_DIR' '$REMOTE_LOG_DIR'
if docker ps -a --format '{{.Names}}' | grep -qx '$SERVICE_NAME'; then
  current_image=\$(docker inspect -f '{{.Config.Image}}' '$SERVICE_NAME')
  echo \"\$current_image\" | sudo tee '/opt/pool-ai/last_image_${SERVICE_NAME}.txt' >/dev/null || true
fi
docker pull '$IMAGE'
if docker ps -a --format '{{.Names}}' | grep -qx '$SERVICE_NAME'; then
  docker rm -f '$SERVICE_NAME' >/dev/null 2>&1 || true
fi
docker run -d \
  --name '$SERVICE_NAME' \
  --restart unless-stopped \
  --network host \
  -v '$REMOTE_CONFIG_DIR:/app/config' \
  -v '$REMOTE_LOG_DIR:/app/logs' \
  -e SPRING_CONFIG_LOCATION=/app/config/application-prod.yml \
  ${ports_args} \
  $( [[ -n "$ENV_FILE" ]] && echo "--env-file '$REMOTE_CONFIG_DIR/.env'" ) \
  '$IMAGE'

deadline=\$((\$(date +%s) + $HEALTH_WAIT))
ok=0
while [[ \$(date +%s) -lt \$deadline ]]; do
  if curl -fsS --max-time 2 '$HEALTH_URL' >/dev/null 2>&1; then
    ok=1
    break
  fi
  sleep '$HEALTH_INTERVAL'
done
if [[ \$ok -ne 1 ]]; then
  echo 'health check failed'
  docker logs --tail 120 '$SERVICE_NAME' || true
  exit 2
fi
docker ps --filter 'name=$SERVICE_NAME' --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}'
"
  run_ssh "$user" "$host" "$port" "$cmd"
}

rollback_host() {
  local name="$1" host="$2" port="$3" user="$4"
  local ports_args
  ports_args="$(build_ports_args)"
  echo "[$name] Rolling back container $SERVICE_NAME"

  local cmd="
set -euo pipefail
last_file='/opt/pool-ai/last_image_${SERVICE_NAME}.txt'
[[ -f \"\$last_file\" ]] || { echo 'no previous image recorded'; exit 1; }
prev_image=\$(cat \"\$last_file\")
[[ -n \"\$prev_image\" ]] || { echo 'previous image empty'; exit 1; }
docker pull \"\$prev_image\"
if docker ps -a --format '{{.Names}}' | grep -qx '$SERVICE_NAME'; then
  docker rm -f '$SERVICE_NAME' >/dev/null 2>&1 || true
fi
docker run -d \
  --name '$SERVICE_NAME' \
  --restart unless-stopped \
  --network host \
  -v '$REMOTE_CONFIG_DIR:/app/config' \
  -v '$REMOTE_LOG_DIR:/app/logs' \
  -e SPRING_CONFIG_LOCATION=/app/config/application-prod.yml \
  ${ports_args} \
  '$prev_image'
docker ps --filter 'name=$SERVICE_NAME' --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}'
"
  run_ssh "$user" "$host" "$port" "$cmd"
}

status_host() {
  local name="$1" host="$2" port="$3" user="$4"
  echo "[$name] Status"
  run_ssh "$user" "$host" "$port" "docker ps -a --filter 'name=$SERVICE_NAME' --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}\t{{.RunningFor}}'"
}

logs_host() {
  local name="$1" host="$2" port="$3" user="$4"
  echo "[$name] Logs"
  run_ssh "$user" "$host" "$port" "docker logs --tail $LINES '$SERVICE_NAME'"
}

restart_host() {
  local name="$1" host="$2" port="$3" user="$4"
  echo "[$name] Restart"
  run_ssh "$user" "$host" "$port" "docker restart '$SERVICE_NAME' && docker ps --filter 'name=$SERVICE_NAME' --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}'"
}

batch() {
  local failed=()
  local total=0
  while IFS='|' read -r name host port user; do
    [[ -n "${name:-}" ]] || continue
    total=$((total + 1))

    if [[ "$ACTION" == "deploy" && -n "$ENV_FILE" ]]; then
      run_ssh "$user" "$host" "$port" "sudo mkdir -p '$REMOTE_CONFIG_DIR' && sudo chown -R $user:$user /opt/pool-ai"
      run_scp "$ENV_FILE" "$user" "$host" "$port" "$REMOTE_CONFIG_DIR/.env" || true
    fi

    case "$ACTION" in
      deploy) deploy_host "$name" "$host" "$port" "$user" || failed+=("$name($host)") ;;
      rollback) rollback_host "$name" "$host" "$port" "$user" || failed+=("$name($host)") ;;
      status) status_host "$name" "$host" "$port" "$user" || failed+=("$name($host)") ;;
      logs) logs_host "$name" "$host" "$port" "$user" || failed+=("$name($host)") ;;
      restart) restart_host "$name" "$host" "$port" "$user" || failed+=("$name($host)") ;;
      *) echo "Unsupported action: $ACTION" >&2; exit 1 ;;
    esac
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
  validate
  setup_ssh_opts
  batch
}

main "$@"
