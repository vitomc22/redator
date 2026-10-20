#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

# Variáveis fornecidas pelo launcher/usuário sempre vencem os defaults do .env.
override_names=()
override_values=()
for name in OLLAMA_BASE_URL OLLAMA_MODEL OLLAMA_MODEL_DIGEST OLLAMA_MODEL_QUANTIZATION \
  OLLAMA_VERSION OLLAMA_RUNTIME OLLAMA_AUTO_INSTALL OLLAMA_INSTALLER_SHA256 OLLAMA_AUTO_PULL OLLAMA_STARTUP_TIMEOUT OLLAMA_PULL_TIMEOUT \
  RAG_DATABASE REDATOR_DATA_DIR; do
  if value="$(printenv "$name" 2>/dev/null)"; then
    override_names+=("$name")
    override_values+=("$value")
  fi
done
if [[ -f .env ]]; then
  set -a
  # Arquivo local do projeto, ignorado pelo Git.
  source .env
  set +a
fi
for index in "${!override_names[@]}"; do
  export "${override_names[$index]}=${override_values[$index]}"
done

for dependency in curl jq; do
  command -v "$dependency" >/dev/null || {
    echo "Pré-requisito ausente: $dependency" >&2
    exit 1
  }
done

compose_file="$project_dir/compose.ollama.yml"
base_url="${OLLAMA_BASE_URL:-http://127.0.0.1:11434}"
model="${OLLAMA_MODEL:-gemma4:e2b-it-q4_K_M}"
wait_seconds="${OLLAMA_STARTUP_TIMEOUT:-120}"
rag_database="${RAG_DATABASE:-dataset/public-corpus.db}"
runtime_mode="${OLLAMA_RUNTIME:-auto}"
data_dir="${REDATOR_DATA_DIR:-${XDG_DATA_HOME:-${HOME}/.local/share}/redator-enem}"
runtime_dir="$data_dir/runtime"
log_dir="$data_dir/logs"

[[ -s "$rag_database" ]] || {
  echo "Corpus RAG obrigatório ausente ou vazio: $rag_database" >&2
  echo "Execute a ingestão do corpus humano antes de iniciar o portal." >&2
  exit 1
}

case "$base_url" in
  http://127.0.0.1:*|http://localhost:*) ;;
  *) echo "OLLAMA_BASE_URL deve apontar para loopback no perfil dev-local." >&2; exit 1 ;;
esac
[[ "$wait_seconds" =~ ^[1-9][0-9]*$ ]] || { echo "OLLAMA_STARTUP_TIMEOUT inválido." >&2; exit 1; }
case "$runtime_mode" in auto|native|docker) ;; *) echo "OLLAMA_RUNTIME deve ser auto, native ou docker." >&2; exit 1 ;; esac

start_native() {
  command -v ollama >/dev/null 2>&1 || return 1
  mkdir -p "$runtime_dir" "$log_dir"
  umask 077
  echo "[local] Iniciando Ollama nativo..."
  OLLAMA_HOST="${base_url#http://}" OLLAMA_NO_CLOUD=1 nohup ollama serve \
    >>"$log_dir/ollama.log" 2>&1 &
  printf '%s\n' "$!" > "$runtime_dir/ollama.pid"
}

install_native() {
  [[ "${OLLAMA_AUTO_INSTALL:-true}" == true ]] || return 1
  local installer checksum expected_checksum
  installer="$(mktemp)"
  expected_checksum="${OLLAMA_INSTALLER_SHA256:-25f64b810b947145095956533e1bdf56eacea2673c55a7e586be4515fc882c9f}"
  echo "[local] Baixando o instalador oficial fixado do Ollama..."
  if ! curl --fail --show-error --location --proto '=https' --tlsv1.2 \
      https://ollama.com/install.sh -o "$installer"; then
    rm -f "$installer"
    return 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    checksum="$(sha256sum "$installer" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    checksum="$(shasum -a 256 "$installer" | awk '{print $1}')"
  else
    rm -f "$installer"
    echo "sha256sum ou shasum é obrigatório para verificar o instalador." >&2
    return 1
  fi
  if [[ "$checksum" != "$expected_checksum" ]]; then
    rm -f "$installer"
    echo "O instalador oficial mudou e não corresponde ao hash aprovado; instalação recusada." >&2
    return 1
  fi
  echo "[local] Instalando Ollama ${OLLAMA_VERSION:-0.40.4}; o sistema pode solicitar sua senha administrativa..."
  if ! OLLAMA_VERSION="${OLLAMA_VERSION:-0.40.4}" OLLAMA_NO_START=1 bash "$installer"; then
    rm -f "$installer"
    return 1
  fi
  rm -f "$installer"
  command -v ollama >/dev/null 2>&1
}

start_docker() {
  local -a compose
  command -v docker >/dev/null 2>&1 || return 1
  if docker compose version >/dev/null 2>&1; then
    compose=(docker compose)
  elif command -v docker-compose >/dev/null && docker-compose version >/dev/null 2>&1; then
    compose=(docker-compose)
  else
    return 1
  fi
  echo "[local] Subindo Ollama pelo Docker Compose..."
  "${compose[@]}" -f "$compose_file" up -d ollama
}

version_payload=""
if version_payload="$(curl --fail --silent --show-error --connect-timeout 2 "$base_url/api/version" 2>/dev/null)"; then
  echo "[local] Ollama já está disponível; preservando o runtime atual."
else
  started=false
  if [[ "$runtime_mode" != docker ]] && start_native; then started=true; fi
  if [[ "$started" != true && "$runtime_mode" != docker ]] && install_native && start_native; then started=true; fi
  if [[ "$started" != true && "$runtime_mode" != native ]] && start_docker; then started=true; fi
  if [[ "$started" != true ]]; then
    echo "Ollama não foi encontrado. Instale o runtime nativo ou habilite Docker Compose." >&2
    exit 1
  fi

  deadline=$((SECONDS + wait_seconds))
  until curl --fail --silent --show-error --connect-timeout 2 "$base_url/api/version" >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      echo "Ollama não ficou disponível em ${wait_seconds}s." >&2
      [[ -f "$log_dir/ollama.log" ]] && tail -40 "$log_dir/ollama.log" >&2 || true
      exit 1
    fi
    sleep 2
  done
fi

version_payload="$(curl --fail --silent --show-error --connect-timeout 3 "$base_url/api/version")"
runtime_version="$(jq -er '.version' <<<"$version_payload")"
expected_runtime_version="${OLLAMA_VERSION:-0.40.4}"
[[ "$runtime_version" == "$expected_runtime_version" ]] || {
  echo "Versão do Ollama diverge do manifesto aprovado: esperado $expected_runtime_version, encontrado $runtime_version." >&2
  exit 1
}

tags="$(curl --fail --silent --show-error --connect-timeout 3 "$base_url/api/tags")"
if ! jq -e --arg model "$model" '.models[]? | select(.name == $model or .model == $model)' <<<"$tags" >/dev/null; then
  [[ "${OLLAMA_AUTO_PULL:-true}" == "true" ]] || {
    echo "Modelo obrigatório ausente e OLLAMA_AUTO_PULL=false: $model" >&2
    exit 1
  }
  echo "[local] Baixando o Gemma obrigatório $model (o Ollama permite retomar camadas já recebidas)..."
  pull_payload="$(jq -cn --arg model "$model" '{model:$model,stream:false}')"
  curl --fail-with-body --silent --show-error --connect-timeout 3 \
    --max-time "${OLLAMA_PULL_TIMEOUT:-1800}" -H 'Content-Type: application/json' \
    --data-binary "$pull_payload" "$base_url/api/pull" >/dev/null || {
      echo "Falha ao baixar $model. O backend não será iniciado e nenhum provider de nuvem será usado." >&2
      exit 1
    }
  tags="$(curl --fail --silent --show-error --connect-timeout 3 "$base_url/api/tags")"
  jq -e --arg model "$model" '.models[]? | select(.name == $model or .model == $model)' <<<"$tags" >/dev/null || {
    echo "Download terminou sem disponibilizar o modelo obrigatório: $model" >&2
    exit 1
  }
fi

show_payload="$(jq -cn --arg model "$model" '{model:$model}')"
show="$(curl --fail --silent --show-error --connect-timeout 3 \
  -H 'Content-Type: application/json' --data-binary "$show_payload" "$base_url/api/show")"
jq -e '.capabilities | index("completion") != null' <<<"$show" >/dev/null || {
  echo "Modelo $model não declara capacidade de completion." >&2
  exit 1
}
quantization="$(jq -er '.details.quantization_level' <<<"$show")"
expected_quantization="${OLLAMA_MODEL_QUANTIZATION:-Q4_K_M}"
[[ "$quantization" == "$expected_quantization" ]] || {
  echo "Quantização do modelo diverge do manifesto aprovado: esperado $expected_quantization, encontrado $quantization." >&2
  exit 1
}

digest="$(jq -r --arg model "$model" '.models[] | select(.name == $model or .model == $model) | .digest' <<<"$tags")"
expected_digest="${OLLAMA_MODEL_DIGEST:-b37049369adfe3d2b653af0ab301a062ca5cbe96ace6aa0d3f2559ee9b563fc2}"
[[ "${digest#sha256:}" == "${expected_digest#sha256:}" ]] || {
  echo "Digest do modelo diverge do manifesto aprovado; recusando inicialização." >&2
  exit 1
}
echo "[local] Ollama $runtime_version pronto: $model / $quantization (${digest:0:12}...)"
