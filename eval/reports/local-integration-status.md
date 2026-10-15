# Integração local — registro de execução

## Ponto de partida inspecionado em 15/10/2026

- `LlmGateway` só retornava resposta fixa por competência (`fake`).
- `EssayService.evaluateEssay` pontuava por heurística e apenas anexava o JSON fake às evidências.
- A transação envolvia toda a avaliação: incompatível com inferência local longa e pool SQLite de uma conexão.
- `getResult` não vinculava a consulta a uma revisão imutável do texto.
- O catálogo continha temas, não um corpus humano elegível.

## Ambiente observado

- `command -v docker`: executável não encontrado.
- `command -v ollama`: executável não encontrado.
- `GET http://127.0.0.1:11434/api/version`: conexão recusada.
- `free -h`: 17 GiB de memória total; aproximadamente 11 GiB disponíveis no instante da inspeção.

Não foi possível executar Gemma neste estado. Disponibilidade de memória em repouso
não é medição de pico da inferência. Não há digest de runtime aprovado, latência,
tokens/s ou concordância humana medidos nesta entrega sem execução real.

## Critério de promoção

A integração técnica deve passar pelos testes offline. O smoke real e o percurso
API → modelo → persistência ainda precisam ser executados com Docker/Ollama.
Em seguida, medir execuções fria/quentes e avaliar redações elegíveis. Não promover
qualidade pedagógica com fixtures, fake ou apenas sucesso estrutural de JSON.

O plano original do usuário permanece inalterado; este arquivo registra evidências,
não substitui os critérios de aceite do [plano v3](../../plano_corretor_enem_ia_mvp_v2.md).
