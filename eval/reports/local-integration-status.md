# Integração local — registro de execução

## Ponto de partida inspecionado em 15/10/2026

- `LlmGateway` só retornava resposta fixa por competência (`fake`).
- `EssayService.evaluateEssay` pontuava por heurística e apenas anexava o JSON fake às evidências.
- A transação envolvia toda a avaliação: incompatível com inferência local longa e pool SQLite de uma conexão.
- `getResult` não vinculava a consulta a uma revisão imutável do texto.
- O catálogo continha temas, não um corpus humano elegível.

## Ambiente inicialmente observado

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

## Validação real concluída em 15/10/2026

- Docker 29.1.3 e Compose 1.29.2, com Ollama isolado em `127.0.0.1:11434`;
- imagem oficial fixada em `ollama/ollama@sha256:926d40fd6eeeebd346b5c358a63de2a10ec995371cd93dd22f1843200694b1a6`;
- Ollama 0.40.4 e modelo `gemma4:e2b-it-q4_K_M` de 4.6B/Q4_K_M;
- digest do modelo `b37049369adfe3d2b653af0ab301a062ca5cbe96ace6aa0d3f2559ee9b563fc2`;
- smoke estruturado passou com `think=false`;
- modelo permaneceu disponível, com o mesmo digest, depois do reinício do container;
- limite rígido de memória/swap de 10 GiB; pico observado de 5,05 GiB durante inferência;
- E2E Spring/API → Ollama Docker → contrato → SQLite passou em 149,5 s e registrou exatamente uma chamada LLM.

Na primeira execução E2E, o modelo alterou uma citação e o sistema recusou corretamente
a publicação com `INVALID_EVIDENCE`. O contrato foi endurecido para fornecer candidatos
literais no schema dinâmico; a repetição real passou sem relaxar a validação.

O lock técnico foi promovido. Isso comprova integração e fail-closed, não concordância
com corretores humanos. A promoção pedagógica continua bloqueada até benchmark cego,
autorizado e revisado por competência, conforme o plano.

O plano original do usuário permanece inalterado; este arquivo registra evidências,
não substitui os critérios de aceite do [plano v3](../../plano_corretor_enem_ia_mvp_v2.md).
