#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

if [[ -f .env ]]; then
  set -a
  # Arquivo local do projeto, ignorado pelo Git.
  source .env
  set +a
fi

for dependency in docker curl jq; do
  command -v "$dependency" >/dev/null || {
    echo "Pré-requisito ausente: $dependency" >&2
    exit 1
  }
done
if docker compose version >/dev/null 2>&1; then
  compose=(docker compose)
elif command -v docker-compose >/dev/null && docker-compose version >/dev/null 2>&1; then
  compose=(docker-compose)
else
  echo "Pré-requisito ausente: Docker Compose (plugin v2 ou docker-compose v1)." >&2
  exit 1
fi

compose_file="$project_dir/compose.ollama.yml"
base_url="${OLLAMA_BASE_URL:-http://127.0.0.1:11434}"
model="${OLLAMA_MODEL:-gemma4:e2b-it-q4_K_M}"
wait_seconds="${OLLAMA_STARTUP_TIMEOUT:-120}"
rag_database="${RAG_DATABASE:-dataset/public-corpus.db}"

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

if curl --fail --silent --show-error --connect-timeout 2 "$base_url/api/version" >/dev/null 2>&1; then
  echo "[dev-local] Ollama já está disponível; preservando o container atual."
else
  echo "[dev-local] Subindo Ollama pelo Docker Compose..."
  if ! "${compose[@]}" -f "$compose_file" up -d ollama; then
    echo "Não foi possível acessar o daemon Docker. Verifique se o serviço está ativo e se o usuário possui acesso ao socket." >&2
    exit 1
  fi

  deadline=$((SECONDS + wait_seconds))
  until curl --fail --silent --show-error --connect-timeout 2 "$base_url/api/version" >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      echo "Ollama não ficou disponível em ${wait_seconds}s." >&2
      "${compose[@]}" -f "$compose_file" ps ollama >&2 || true
      exit 1
    fi
    sleep 2
  done
fi

tags="$(curl --fail --silent --show-error --connect-timeout 3 "$base_url/api/tags")"
if ! jq -e --arg model "$model" '.models[]? | select(.name == $model or .model == $model)' <<<"$tags" >/dev/null; then
  echo "Modelo obrigatório ausente no volume do Ollama: $model" >&2
  echo "Esse tag Gemma é local/customizado; importe ou crie o modelo antes de iniciar o backend." >&2
  echo "Modelos disponíveis:" >&2
  jq -r '.models[]?.name' <<<"$tags" >&2
  exit 1
fi

show_payload="$(jq -cn --arg model "$model" '{model:$model}')"
show="$(curl --fail --silent --show-error --connect-timeout 3 \
  -H 'Content-Type: application/json' --data-binary "$show_payload" "$base_url/api/show")"
jq -e '.capabilities | index("completion") != null' <<<"$show" >/dev/null || {
  echo "Modelo $model não declara capacidade de completion." >&2
  exit 1
}

digest="$(jq -r --arg model "$model" '.models[] | select(.name == $model or .model == $model) | .digest' <<<"$tags")"
echo "[dev-local] Ollama pronto: $model (${digest:0:12}...)"
