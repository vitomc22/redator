# E2E — desinformação — 2026-10-16

Caso autorizado pelo usuário, com referência humana de 800 pontos (160 em cada competência).

## Execução real

- Essay ID: 719
- Run ID válido: 466
- Provider/modelo: `ollama` / `gemma4:e2b-it-q4_K_M`
- Digest: `b37049369adfe3d2b653af0ab301a062ca5cbe96ace6aa0d3f2559ee9b563fc2`
- Runtime Ollama: 0.40.4
- Configuração: contexto 8192, saída 1200, temperatura 0, seed 42
- Latência persistida: 230812 ms
- Cache: não
- Status: `READY`, sem revisão obrigatória

| Competência | Humano | IA | Erro assinado | Erro absoluto |
|---|---:|---:|---:|---:|
| C1 | 160 | 160 | 0 | 0 |
| C2 | 160 | 160 | 0 | 0 |
| C3 | 160 | 200 | +40 | 40 |
| C4 | 160 | 160 | 0 | 0 |
| C5 | 160 | 200 | +40 | 40 |
| Total | 800 | 880 | +80 | 80 |

O modelo acertou exatamente três das cinco competências e superestimou C3 e C5 em um nível. Este único caso passa no limite de regressão E2E de 80 pontos, mas não demonstra calibração geral nem substitui benchmark com amostra representativa e dupla correção humana.

## Incidente encontrado e corrigido

A primeira chamada, run 465, foi rejeitada antes da inferência com HTTP 422 `CONTEXT_LIMIT`: o contexto de 4096 não acomodava proposta, redação, rubrica, schema e reserva de saída. O contexto local foi elevado para 8192; não houve truncamento nem nota fabricada.

## Playwright

- Dois testes rápidos passaram no Chromium: fluxo de envio/inspeção/resultado e bloqueio de formulário vazio.
- Um teste `@live` passou sobre o resultado persistido: cinco competências, comparação “880 IA · 800 humano” e diagnóstico Ollama/Gemma.
- O cenário integral `@live` que dispara outra inferência permanece opt-in para não adicionar vários minutos ao ciclo normal de CI.
