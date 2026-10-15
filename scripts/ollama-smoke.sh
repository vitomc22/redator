#!/usr/bin/env bash
# Opt-in local: não baixa modelo, não modifica lock, não faz retry.
set -euo pipefail
for dependency in curl jq; do
  command -v "$dependency" >/dev/null || { echo "Falta dependência: $dependency" >&2; exit 1; }
done
base_url="${OLLAMA_BASE_URL:-http://127.0.0.1:11434}"
model="${OLLAMA_MODEL:-gemma4:e2b-it-q4_K_M}"
timeout="${OLLAMA_SMOKE_TIMEOUT:-600}"
case "$base_url" in
  http://127.0.0.1:*|http://localhost:*) ;;
  *) echo 'Smoke aceita apenas endpoint HTTP local em loopback.' >&2; exit 1 ;;
esac
[[ "$timeout" =~ ^[1-9][0-9]*$ ]] || { echo 'Timeout inválido' >&2; exit 1; }
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
request() {
  curl --fail-with-body --silent --show-error --connect-timeout 3 --max-time "$timeout" "$@"
}
request "$base_url/api/version" > "$work_dir/version.json"
jq -e '.version | type == "string" and length > 0' "$work_dir/version.json" >/dev/null
request "$base_url/api/tags" > "$work_dir/tags.json"
jq -e --arg model "$model" '.models[] | select(.name == $model or .model == $model) | .digest | test("^(sha256:)?[a-f0-9]{64}$")' "$work_dir/tags.json" >/dev/null || {
  echo 'Modelo ausente ou digest completo indisponível. Faça o pull explicitamente.' >&2; exit 1;
}
jq -n --arg model "$model" '{model:$model}' > "$work_dir/show-request.json"
request -H 'Content-Type: application/json' --data-binary "@$work_dir/show-request.json" "$base_url/api/show" > "$work_dir/show.json"
jq -e '.details.quantization_level == "Q4_K_M"' "$work_dir/show.json" >/dev/null || {
  echo 'Quantização diferente de Q4_K_M ou metadado ausente.' >&2; exit 1;
}
# Schema sintético deliberadamente pequeno: verifica structured output, não qualidade ENEM.
jq -n --arg model "$model" '{model:$model, stream:false, think:false, keep_alive:"5m",
  messages:[{role:"user",content:"Responda somente com o objeto JSON solicitado: status deve ser ok."}],
  format:{type:"object",additionalProperties:false,required:["status"],properties:{status:{type:"string",enum:["ok"]}}},
  options:{num_ctx:4096,num_predict:1200,num_thread:4,temperature:0,seed:42}}' > "$work_dir/chat-request.json"
if ! request -H 'Content-Type: application/json' --data-binary "@$work_dir/chat-request.json" "$base_url/api/chat" > "$work_dir/chat.json"; then
  echo 'Falha na geração. Sem retry: após timeout, o servidor pode continuar ocupado.' >&2; exit 1
fi
jq -e '.done == true and .done_reason == "stop" and .message.role == "assistant" and
  ((.message.thinking // "") == "") and
  ((.message.content | fromjson) == {status:"ok"})' "$work_dir/chat.json" >/dev/null || {
  echo 'Envelope, término, thinking ou JSON/schema inválido.' >&2; exit 1;
}
echo 'PASS: geração estruturada sintética local. NÃO aprova qualidade ENEM, persistência ou lock.'
jq '{serverVersion:.version}' "$work_dir/version.json"
jq --arg model "$model" '.models[] | select(.name == $model or .model == $model) | {model:.name,digest,details}' "$work_dir/tags.json"
echo 'Registre imagem/digest e hardware reais; teste reinício, limites e aplicação antes de promover o lock.'
