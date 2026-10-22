#!/usr/bin/env bash
set -euo pipefail

readonly PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly COMMAND="${1:-run}"
shift || true

FAKE=false
for argument in "$@"; do
  case "$argument" in
    --fake) FAKE=true ;;
    *) echo "Argumento desconhecido: $argument" >&2; exit 64 ;;
  esac
done

default_data_directory() {
  case "$(uname -s)" in
    Darwin) printf '%s\n' "${HOME}/Library/Application Support/Redator ENEM" ;;
    *) printf '%s\n' "${XDG_DATA_HOME:-${HOME}/.local/share}/redator-enem" ;;
  esac
}

configured_data="${REDATOR_DATA_DIR:-$(default_data_directory)}"
[[ "$configured_data" == /* ]] || configured_data="$PWD/$configured_data"
readonly DATA_DIR="$configured_data"
readonly RUNTIME_DIR="${DATA_DIR}/runtime"
readonly LOG_DIR="${DATA_DIR}/logs"
readonly PID_FILE="${RUNTIME_DIR}/redator.pid"
readonly PORT_FILE="${RUNTIME_DIR}/redator.port"
readonly JAR_FILE="${RUNTIME_DIR}/redator.jar-path"
if [[ -n "${REDATOR_JAVA_BIN:-}" ]]; then
  readonly JAVA_BIN="$REDATOR_JAVA_BIN"
elif [[ -x "$PROJECT_DIR/runtime/bin/java" ]]; then
  readonly JAVA_BIN="$PROJECT_DIR/runtime/bin/java"
else
  readonly JAVA_BIN=java
fi

prepare_layout() {
  umask 077
  mkdir -p "$RUNTIME_DIR" "$LOG_DIR" "$DATA_DIR/profiles" "$DATA_DIR/corpus" \
    "$DATA_DIR/backups" "$DATA_DIR/exchange/inbox" "$DATA_DIR/exchange/outbox"
  chmod 700 "$DATA_DIR" "$RUNTIME_DIR" "$LOG_DIR" 2>/dev/null || true
}

java_major_version() {
  "$JAVA_BIN" -version 2>&1 | awk -F '[".]' '/version/ { if ($2 == "1") print $3; else print $2; exit }'
}

verify_java() {
  command -v "$JAVA_BIN" >/dev/null 2>&1 || { echo "Java 21 não encontrado." >&2; exit 69; }
  local major
  major="$(java_major_version)"
  [[ "$major" =~ ^[0-9]+$ ]] && (( major >= 21 )) || {
    echo "Java 21 ou superior é obrigatório (encontrado: ${major:-desconhecido})." >&2
    exit 69
  }
}

verify_launcher_tools() {
  command -v curl >/dev/null 2>&1 || { echo "curl não encontrado." >&2; exit 69; }
}

port_is_free() {
  local port="$1"
  ! (exec 3<>"/dev/tcp/127.0.0.1/${port}") 2>/dev/null
}

select_port() {
  local requested="${REDATOR_PORT:-8080}"
  if [[ -n "${REDATOR_PORT:-}" ]]; then
    port_is_free "$requested" || { echo "A porta ${requested} já está em uso." >&2; exit 69; }
    printf '%s\n' "$requested"
    return
  fi
  local candidate
  for candidate in $(seq 8080 8180); do
    if port_is_free "$candidate"; then printf '%s\n' "$candidate"; return; fi
  done
  echo "Nenhuma porta local disponível entre 8080 e 8180." >&2
  exit 69
}

managed_process() {
  [[ -f "$PID_FILE" ]] || return 1
  local pid command_line jar
  pid="$(tr -dc '0-9' < "$PID_FILE")"
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null || return 1
  command_line="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  if [[ -f "$JAR_FILE" ]]; then
    IFS= read -r jar < "$JAR_FILE" || [[ -n "$jar" ]]
    [[ -n "$jar" && "$command_line" == *" -jar $jar" ]]
    return
  fi
  [[ "$command_line" == *"corretor-enem"* || "$command_line" == *"com.redator.corretor"* ]]
}

resolve_jar() {
  local jar
  if [[ -n "${REDATOR_APP_JAR:-}" ]]; then
    jar="$REDATOR_APP_JAR"
    [[ -f "$jar" ]] || { echo "Pacote configurado não encontrado: $jar" >&2; exit 69; }
  elif [[ -f "$PROJECT_DIR/app/redator.jar" ]]; then
    jar="$PROJECT_DIR/app/redator.jar"
  else
    jar="$PROJECT_DIR/target/corretor-enem-0.1.0-SNAPSHOT.jar"
  fi
  if [[ ! -f "$jar" ]]; then
    command -v mvn >/dev/null 2>&1 || { echo "Maven não encontrado e pacote da aplicação ausente." >&2; exit 69; }
    (cd "$PROJECT_DIR" && mvn -q -DskipTests package) >&2
  fi
  [[ -f "$jar" ]] || { echo "Pacote executável não foi gerado." >&2; exit 70; }
  printf '%s/%s\n' "$(cd "$(dirname "$jar")" && pwd)" "$(basename "$jar")"
}

verify_runtime() {
  if [[ "$FAKE" == true ]]; then return; fi
  "$PROJECT_DIR/scripts/ollama-prerequisites.sh"
}

application_command() {
  local jar="$1" port="$2"
  cd "$PROJECT_DIR"
  REDATOR_DATA_DIR="$DATA_DIR" SERVER_PORT="$port" SERVER_ADDRESS="127.0.0.1" \
    RAG_DATABASE="${RAG_DATABASE:-$PROJECT_DIR/dataset/public-corpus.db}" \
    LLM_PROVIDER="$([[ "$FAKE" == true ]] && printf fake || printf ollama)" \
    exec "$JAVA_BIN" -jar "$jar"
}

case "$COMMAND" in
  check)
    prepare_layout
    verify_java
    verify_launcher_tools
    port="$(select_port)"
    printf 'status=ready\nprovider=%s\naddress=127.0.0.1\nport=%s\ndata=%s\n' \
      "$([[ "$FAKE" == true ]] && printf fake || printf ollama)" "$port" "$DATA_DIR"
    ;;
  status)
    prepare_layout
    if managed_process; then
      printf 'status=running\npid=%s\nport=%s\n' "$(cat "$PID_FILE")" "$(cat "$PORT_FILE" 2>/dev/null || printf unknown)"
    else
      rm -f "$PID_FILE" "$PORT_FILE" "$JAR_FILE"
      printf 'status=stopped\n'
    fi
    ;;
  stop)
    prepare_layout
    if ! managed_process; then
      rm -f "$PID_FILE" "$PORT_FILE" "$JAR_FILE"
      printf 'status=stopped\nreason=stale-pid\n'
      exit 0
    fi
    pid="$(cat "$PID_FILE")"
    kill -TERM "$pid"
    for _ in $(seq 1 100); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.1
    done
    if kill -0 "$pid" 2>/dev/null; then
      echo "O backend não encerrou no prazo; nenhum SIGKILL foi enviado." >&2
      exit 70
    fi
    rm -f "$PID_FILE" "$PORT_FILE" "$JAR_FILE"
    printf 'status=stopped\n'
    ;;
  run|start)
    prepare_layout
    verify_java
    verify_launcher_tools
    if managed_process; then
      echo "O Redator já está em execução (PID $(cat "$PID_FILE"))." >&2
      exit 69
    fi
    rm -f "$PID_FILE" "$PORT_FILE" "$JAR_FILE"
    port="$(select_port)"
    verify_runtime
    jar="$(resolve_jar)"
    printf '%s\n' "$jar" > "$JAR_FILE"
    if [[ "$COMMAND" == run ]]; then
      printf '%s\n' "$port" > "$PORT_FILE"
      trap 'rm -f "$PID_FILE" "$PORT_FILE" "$JAR_FILE"' EXIT INT TERM
      printf '%s\n' "$$" > "$PID_FILE"
      echo "Redator disponível em http://127.0.0.1:${port}/"
      application_command "$jar" "$port"
    fi
    (
      application_command "$jar" "$port"
    ) >>"$LOG_DIR/redator.log" 2>&1 &
    pid=$!
    printf '%s\n' "$pid" > "$PID_FILE"
    printf '%s\n' "$port" > "$PORT_FILE"
    ready=false
    for _ in $(seq 1 "${REDATOR_STARTUP_ATTEMPTS:-120}"); do
      if curl -fsS "http://127.0.0.1:${port}/" >/dev/null 2>&1; then ready=true; break; fi
      if ! kill -0 "$pid" 2>/dev/null; then break; fi
      sleep 0.25
    done
    if [[ "$ready" != true ]]; then
      rm -f "$PID_FILE" "$PORT_FILE" "$JAR_FILE"
      kill -TERM "$pid" 2>/dev/null || true
      echo "O backend não ficou pronto; consulte $LOG_DIR/redator.log" >&2
      exit 70
    fi
    printf 'status=started\npid=%s\nurl=http://127.0.0.1:%s/\nlog=%s\n' "$pid" "$port" "$LOG_DIR/redator.log"
    ;;
  *)
    echo "Uso: $0 {check|run|start|status|stop} [--fake]" >&2
    exit 64
    ;;
esac
