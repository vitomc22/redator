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
- [ ] Substituir os demais E2E críticos interceptados por integrações reais; histórico, checklist e reescrita já usam Spring + SQLite reais.

## Fase 1 — núcleo local-first

- [x] Diretório de dados configurável e layout local seguro, pronto para o launcher escolher o caminho por sistema operacional.
- [ ] Banco privado por perfil e migração do banco legado.
- [x] Backup consistente, importação pela UI e restauração aplicada antes do datasource, com snapshot de segurança.
- [x] Launcher local com preflight, diretório privado, porta loopback livre, modos foreground/background, PID validado, status, logs e encerramento seguro.
- [x] Exportação/importação integral da coleção SQLite pela interface, com validação de integridade e schema.

## Fase 2 — instalação Gemma

- [x] Manifesto versionado de Ollama/Gemma com imagem, digest e quantização fixados.
- [x] Descoberta e instalação nativa automática do Ollama por instalador oficial versionado e verificado por SHA-256, com Docker como fallback avançado.
- [x] Download automático do Gemma padrão pelo protocolo Ollama, com retomada de camadas e recusa de digest divergente.
- [x] Smoke test de geração estruturada e integração Spring/SQLite/RAG/Gemma real opt-in.
- [ ] Instaladores limpos para Linux, Windows e macOS.

## Fase 3 — avaliação e RAG

- [x] Contrato único e versionado de prompt, rubrica e schema, com hashes incluídos no fingerprint/auditoria.
- [x] Cache SQLite limitado, persistente, com TTL e LRU por fingerprint completo do contrato/configuração.
- [x] Fingerprint lógico determinístico consistente com SQLite WAL/checkpoints.
- [x] Deduplicação exata e aproximada por SimHash, com bloqueio transacional de vazamento entre splits.
- [ ] Benchmark humano congelado em 11 amostras públicas estritamente compatíveis com os seis níveis oficiais, exportação reproduzível, executor pareado auditável e gate por hash/configuração; falta concluir e versionar os dois braços Gemma sem RAG × com RAG e aplicar o gate no release.

## Fase 4 — pacote do professor

- [x] Envelope criptográfico versionado compartilhado por pedido e resposta.
- [x] Cifragem AES-256-GCM, integridade, expiração, revogação e proteção persistente contra replay.
- [x] Importação idempotente e transacional.
- [x] Modo Professor stateless, sem JDBC, datasource ou contexto do perfil do estudante.
- [x] E2E de ida e volta por arquivo, incluindo pacote incompleto e revogação.

## Fase 5 — portal/relay opcional

- [ ] SPA estática do professor.
- [x] Núcleo do relay de blobs opacos com TTL; a integração WebCrypto da SPA ainda está pendente.
- [x] Capacidades separadas, armazenadas apenas como hash, com revogação e consumo único.
- [ ] E2E online e fallback por arquivo.

## Fase 6 — treino estilo Anki

- [x] Cartões derivados das competências publicadas pela IA ou pelo professor.
- [x] Agenda local de repetição espaçada.
- [x] Sessões, metas e progresso diário explicável e isolado por perfil.
- [x] Backup/restauração dos cartões, metas e sessões junto da coleção SQLite integral.

## Fase 7 — distribuição

- [x] Build JAR reproduzível e SBOM CycloneDX 1.6 automático com dependências de runtime.
- [ ] Assinatura/notarização.
- [ ] Atualização assinada e rollback.
- [ ] Matriz pública de hardware e licenças.

## Último baseline

- Java offline: 90 executados, 89 aprovados, 1 teste Ollama/Gemma live ignorado por ser opt-in.
- Java live: `OllamaLiveIntegrationTest` aprovado em 271 s com Ollama 0.40.4 e Gemma `gemma4:e2b-it-q4_K_M` no digest fixado.
- Playwright: 20 aprovados, incluindo isolamento local, histórico/reescrita/checklist persistentes, pacote cifrado do professor, treino espaçado e exportação/restauração contra backend e SQLite reais.
- `git diff --check`: sem erros.
