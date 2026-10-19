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
- [ ] Token efêmero entre UI e API local.
- [ ] Substituir os demais E2E críticos interceptados por integrações reais.

## Fase 1 — núcleo local-first

- [x] Diretório de dados configurável e layout local seguro, pronto para o launcher escolher o caminho por sistema operacional.
- [ ] Banco privado por perfil e migração do banco legado.
- [x] Backup consistente e restauração validada/preparada para aplicação offline pelo launcher.
- [ ] Launcher e lifecycle do backend.
- [ ] Exportação/importação integral da coleção.

## Fase 2 — instalação Gemma

- [x] Manifesto versionado de Ollama/Gemma com imagem, digest e quantização fixados.
- [ ] Descoberta/instalação automática do binário Ollama fora do perfil Docker (Docker já é gerenciado no perfil avançado).
- [x] Download automático do Gemma padrão pelo protocolo Ollama, com retomada de camadas e recusa de digest divergente.
- [x] Smoke test de geração estruturada e integração Spring/SQLite/RAG/Gemma real opt-in.
- [ ] Instaladores limpos para Linux, Windows e macOS.

## Fase 3 — avaliação e RAG

- [ ] Contrato único de prompt e schema.
- [ ] Cache limitado/persistente por fingerprint.
- [ ] Fingerprint consistente com SQLite WAL.
- [ ] Deduplicação aproximada e isolamento de splits.
- [ ] Benchmark humano congelado e gates de qualidade.

## Fase 4 — pacote do professor

- [ ] Envelope versionado de pedido e resposta.
- [ ] Cifragem, integridade, expiração e proteção contra replay.
- [ ] Importação idempotente e transacional.
- [ ] Modo Professor em banco independente.
- [ ] E2E de ida e volta por arquivo.

## Fase 5 — portal/relay opcional

- [ ] SPA estática do professor.
- [ ] Relay de blobs opacos com TTL.
- [ ] Link de capacidade, revogação e uso único.
- [ ] E2E online e fallback por arquivo.

## Fase 6 — treino estilo Anki

- [ ] Cartões derivados das competências.
- [ ] Agenda local de repetição espaçada.
- [ ] Sessões, metas e progresso explicável.
- [ ] Backup/restauração dos cartões.

## Fase 7 — distribuição

- [ ] Builds reprodutíveis e SBOM.
- [ ] Assinatura/notarização.
- [ ] Atualização assinada e rollback.
- [ ] Matriz pública de hardware e licenças.

## Último baseline

- Java offline: 61 executados, 60 aprovados, 1 teste Ollama/Gemma live ignorado por ser opt-in.
- Java live: `OllamaLiveIntegrationTest` aprovado em 271 s com Ollama 0.40.4 e Gemma `gemma4:e2b-it-q4_K_M` no digest fixado.
- Playwright: 14 aprovados, incluindo 1 integração real local-first.
- `git diff --check`: sem erros.
