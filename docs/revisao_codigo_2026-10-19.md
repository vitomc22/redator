# Revisão técnica do código — 19/10/2026

## Escopo e método

A revisão foi paralelizada em três frentes e cobriu os fontes Java, migrações, recursos de prompt/rubrica/schema, frontend, E2E, scripts, configuração de runtime e documentação própria do repositório. Binários, bancos gerados, `target`, `node_modules` e relatórios produzidos por execuções anteriores foram tratados como artefatos, não como código-fonte.

Este documento não afirma que o sistema futuro já foi implementado. Ele registra o estado encontrado e orienta o plano local-first.

## Veredito

O projeto é um protótipo funcional avançado, mas **ainda não está pronto para ser distribuído a usuários finais**. Há backend real, SQLite, integração Ollama/Gemma, corpus público e fluxos de interface. Os bloqueios de lançamento são isolamento incompleto entre perfis, ausência de instalador comum, correção humana acoplada ao mesmo banco, RAG ainda sem gates suficientes e E2E críticos baseados em interceptações.

## Achados prioritários

### P0 — antes de qualquer distribuição

1. **Isolamento por perfil pode ser contornado por IDs.** Consultas de redação, execução e correção humana nem sempre incluem o usuário local. A proteção deve estar no serviço/repositório e ser testada com dois perfis, não depender do controller ou de IDs difíceis de adivinhar.
2. **Servidor não está explicitamente restrito a loopback.** `server.port` existe, mas a instalação local precisa fixar `server.address=127.0.0.1`, controlar CORS e usar token efêmero UI/API.
3. **Publicação de resultados e correções usa múltiplas gravações sem fronteira transacional suficiente.** Falha intermediária pode deixar estado parcial.
4. **O fluxo do professor atual pressupõe acesso ao mesmo banco.** Isso não atende ao requisito de professor remoto limitado a uma redação.

### P1 — antes do beta local-first

1. O script de pré-requisitos verifica um modelo customizado existente, mas não instala automaticamente um Gemma ausente. A instalação atual ainda exige preparação manual/Docker.
2. O “fluxo completo” Playwright intercepta APIs; comprova renderização, não integração real com Spring, SQLite, RAG ou Gemma.
3. O frontend concentra lógica e estado em um HTML extenso, contém redefinições e observadores corretivos. Isso torna transições frágeis e dificulta testes unitários.
4. Troca de perfil pode preservar estado/rascunho do perfil anterior no navegador; cabeçalhos e opções de `fetch` precisam de composição segura.
5. Solicitar correção humana e publicar correção não atualizam consistentemente todos os estados visuais.
6. O fingerprint do corpus lê o arquivo principal do SQLite enquanto WAL pode conter alterações; o identificador pode não representar o estado efetivo.
7. A deduplicação protege hash textual exato, mas falta agrupamento de quase duplicatas e isolamento por tema/fonte entre splits.
8. O cache de avaliações é somente em memória e sem limite explícito; cresce durante sessões longas e não é reaproveitado com segurança após reinício.
9. Existem contratos de resposta/schema em mais de uma camada com risco de divergência.
10. O orçamento de contexto precisa ser testado com caracteres multibyte, exemplos RAG e limites reais do modelo.

### P2 — qualidade e manutenção

1. Rotas antigas e novas coexistem no mesmo controller e aumentam a superfície de compatibilidade.
2. Exceções genéricas perdem códigos de domínio e podem informar incorretamente que nenhuma nota foi publicada.
3. Logs em nível DEBUG e mensagens de erro precisam de política para não registrar redações.
4. O corpus é aberto repetidamente e alguns caminhos de leitura inicializam schema, misturando leitura e mutação.
5. A configuração fixa porta 8080, caminhos relativos e pressupostos Linux/Docker; não é portátil para instaladores.
6. Testes reais do Ollama são opt-in e a asserção pedagógica usa tolerância de uma estimativa, não consenso humano congelado.

## Inventário revisado

### Aplicação Java

- Inicialização: `CorretorEnemApplication`.
- Controllers: `EssayController`, `GlobalExceptionHandler`.
- Configuração: `DocumentTranscriptionInitializer`, `InitialDataLoader`, `LocalUserFilter`, `OllamaProperties`, `RagPrerequisiteInitializer`.
- Modelos: `BenchmarkComparisonRequest`, `BenchmarkRequest`, `CompetenceResult`, `CreateAttemptRequest`, `CreateEssayRequest`, `EssayResponse`, `EvaluationProfile`, `EvaluationResult`, `HumanEvaluationRequest`, `Tema`.
- Serviços de fluxo: `EssayService`, `TrainingCycleService`, `LocalUserContext`.
- IA: `LlmGateway`, `OllamaLlmProvider`, `LocalEvaluationContract`, `ResponseSchemaValidator`, `LlmCallResult`, `EvaluationFailure`, `BudgetGuard`.
- Rubrica/análise: `CartilhaEnem2026Service`, `RubricEngine`, `ThemeAlignmentService`, `DeterministicEssayAnalysis`.
- RAG/corpus: `RagService`, `PublicHumanCorpus`, `ExtendedEssayBrImporter`, `KamelDatasetImporter`, `DatasetManifestValidator`.
- Avaliação: `DatasetMetricsCalculator`, `DatasetReportGenerator`, `BaselineComparisonService`, `AdversarialSuiteService`.
- Documentos/OCR: `DocumentTranscriptionService`, `VisionTranscriptionGateway`, `RealVisionTranscriptionProvider`, `UploadValidationService`, `TranscriptionQualityMetricsCalculator`, `DocumentCalibrationService`, `DocumentCalibrationReportGenerator`.
- CLIs: `PublicCorpusCli`, `BenchmarkDatasetCli`, `DatasetEvaluationCli`, `EvalCli`.

### Persistência e contratos

- Migrações `V1` a `V5`.
- `application.properties`.
- prompts `enem-local-v1-system` e `enem-local-v1-user`.
- rubrica `enem-2026-v1.json`.
- schema `enem-local-v1.json`.
- Cartilha ENEM 2026 em PDF, tratada como documento de referência binário.

### Frontend, execução e documentação

- `static/index.html` e `static/app.css`.
- cinco specs Playwright em `e2e/`.
- `playwright.config.js`, `package.json` e lockfile.
- `pom.xml`, `.env.example`, `compose.ollama.yml`.
- `scripts/ollama-prerequisites.sh` e `scripts/ollama-smoke.sh`.
- manifesto de runtime, documentação de corpus, readiness LoRA, README e plano anterior.

### Testes Java existentes

- `EssayControllerIntegrationTest` e `TrainingCycleIntegrationTest`.
- `OllamaLlmProviderTest` e `OllamaLiveIntegrationTest`.
- `RagServiceTest`, `RagPrerequisiteInitializerTest`, `PublicHumanCorpusTest`.
- `ExtendedEssayBrImporterTest`, `KamelDatasetImporterTest`, `DatasetMetricsCalculatorTest`.

## Testes que devem ser adicionados primeiro

1. Perfil A não lê, edita, avalia, solicita revisão ou consulta run do perfil B.
2. Falha entre gravações faz rollback integral da publicação de avaliação humana e IA.
3. Migração de cada versão anterior preserva dados e constraints.
4. Reinício mantém rascunhos, runs, correções e cache persistente previsto.
5. Frontend não reutiliza texto/IDs ao alternar perfil.
6. E2E real: criação → tentativa → avaliação determinística → histórico, sem `page.route`.
7. E2E real Gemma: contrato, evidências, estados e auditoria, sem exigir nota exata.
8. RAG nunca retorna `validation/test`, quase duplicata ou redação privada.
9. Fingerprint muda após commit WAL e permanece estável sem mudanças.
10. Pacote humano: ida/volta, expiração, adulteração, replay, resposta para revisão errada e importação idempotente.
11. Backup/restore em banco ativo e restauração após migração interrompida.
12. Installer smoke por SO sem Java, Ollama ou Docker previamente instalados.

## Relação com o novo plano

Os achados P0 formam a Fase 0 de `plano_redator_local_first.md`. As lacunas de instalação, RAG e correção humana foram transformadas em fases com critérios de saída mensuráveis. O plano anterior continua como registro histórico, mas não deve orientar novas decisões de arquitetura.

## Ações realizadas durante esta revisão

- Foi criado primeiro um teste de integração com dois perfis locais. Ele falhou porque o perfil B conseguia editar a redação do perfil A.
- O acesso foi corrigido nos caminhos de edição/congelamento, busca de redação, avaliação, consulta de execução e criação pelas rotas legadas. A consulta alheia agora responde `404`, sem revelar a existência do ID.
- O teste de regressão também confirma que o texto original continua intacto e acessível ao proprietário.
- Baseline após a correção: **55 testes Java executados, 54 aprovados e 1 teste real Ollama ignorado por ser opt-in**.
- Baseline atual da interface: **14 testes Playwright aprovados**. Treze exercitam a interface predominantemente com APIs interceptadas; o novo fluxo local-first usa navegador, Spring e SQLite reais, sem interceptação.
- O servidor passou a aceitar conexões apenas em `127.0.0.1`, e rascunhos no navegador passaram a ser separados por perfil local.

O isolamento corrigido reduz o primeiro P0, mas ele só poderá ser encerrado após testes equivalentes cobrirem todo novo endpoint de pacote humano e o modo Professor deixar de consultar o banco privado diretamente.
