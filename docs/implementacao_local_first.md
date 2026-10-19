# Progresso da implementação local-first

Este arquivo acompanha a execução de `plano_redator_local_first.md`. Um item somente muda para concluído quando seus testes automatizados e critérios de saída estiverem verdes.

## Fase 0 — integridade e baseline

- [x] Inventário e revisão paralela do código.
- [x] Teste de integração para acesso cruzado entre dois perfis.
- [x] Propriedade verificada em leitura, edição, avaliação e consulta de execução.
- [x] Rotas legadas registram o proprietário local.
- [x] Servidor limitado a loopback.
- [x] Rascunhos do frontend separados por perfil.
- [x] Primeiro E2E sem interceptação, usando navegador + Spring + SQLite reais.
- [x] Publicação da avaliação de IA inteiramente transacional.
- [x] Testes de rollback para publicação humana e da IA.
- [x] Token efêmero por processo entre UI e API local, em cookie HttpOnly/SameSite Strict.
- [ ] Substituir os demais E2E críticos interceptados por integrações reais.

## Fase 1 — núcleo local-first

- [x] Diretório de dados configurável e layout local seguro, pronto para o launcher escolher o caminho por sistema operacional.
- [ ] Banco privado por perfil e migração do banco legado.
- [x] Backup consistente, importação pela UI e restauração aplicada antes do datasource, com snapshot de segurança.
- [ ] Launcher e lifecycle do backend.
- [x] Exportação/importação integral da coleção SQLite pela interface, com validação de integridade e schema.

## Fase 2 — instalação Gemma

- [x] Manifesto versionado de Ollama/Gemma com imagem, digest e quantização fixados.
- [ ] Descoberta/instalação automática do binário Ollama fora do perfil Docker (Docker já é gerenciado no perfil avançado).
- [x] Download automático do Gemma padrão pelo protocolo Ollama, com retomada de camadas e recusa de digest divergente.
- [x] Smoke test de geração estruturada e integração Spring/SQLite/RAG/Gemma real opt-in.
- [ ] Instaladores limpos para Linux, Windows e macOS.

## Fase 3 — avaliação e RAG

- [ ] Contrato único de prompt e schema.
- [x] Cache SQLite limitado, persistente, com TTL e LRU por fingerprint completo do contrato/configuração.
- [x] Fingerprint lógico determinístico consistente com SQLite WAL/checkpoints.
- [ ] Deduplicação aproximada e isolamento de splits.
- [ ] Benchmark humano congelado e gates de qualidade.

## Fase 4 — pacote do professor

- [x] Envelope criptográfico versionado compartilhado por pedido e resposta.
- [x] Cifragem AES-256-GCM, integridade, expiração, revogação e proteção persistente contra replay.
- [x] Importação idempotente e transacional.
- [ ] Modo Professor em banco independente.
- [x] E2E de ida e volta por arquivo, incluindo pacote incompleto e revogação.

## Fase 5 — portal/relay opcional

- [ ] SPA estática do professor.
- [ ] Relay de blobs opacos com TTL.
- [ ] Link de capacidade, revogação e uso único.
- [ ] E2E online e fallback por arquivo.

## Fase 6 — treino estilo Anki

- [x] Cartões derivados das competências publicadas pela IA ou pelo professor.
- [x] Agenda local de repetição espaçada.
- [x] Sessões, metas e progresso diário explicável e isolado por perfil.
- [x] Backup/restauração dos cartões, metas e sessões junto da coleção SQLite integral.

## Fase 7 — distribuição

- [ ] Builds reprodutíveis e SBOM.
- [ ] Assinatura/notarização.
- [ ] Atualização assinada e rollback.
- [ ] Matriz pública de hardware e licenças.

## Último baseline

- Java offline: 76 executados, 75 aprovados, 1 teste Ollama/Gemma live ignorado por ser opt-in.
- Java live: `OllamaLiveIntegrationTest` aprovado em 271 s com Ollama 0.40.4 e Gemma `gemma4:e2b-it-q4_K_M` no digest fixado.
- Playwright: 19 aprovados, incluindo isolamento local, pacote cifrado do professor, treino espaçado e exportação/restauração contra backend e SQLite reais.
- `git diff --check`: sem erros.
