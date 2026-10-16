# Corretor ENEM IA — MVP experimental

Monólito Java 21/Spring Boot para corrigir redações segundo as cinco competências do ENEM. O runtime real usa Ollama local com `gemma4:e2b-it-q4_K_M`, uma única geração estruturada por redação e auditoria persistida. O provider `fake` continua sendo o padrão seguro para testes offline; suas notas não medem qualidade pedagógica.

O sistema não substitui a correção oficial. Uma nota só é publicada quando a resposta passa pelo schema e não exige revisão; `RUNNING`, `FAILED`, `STALE` e `NEEDS_REVIEW` retornam `total: null`.

## Estado verificável

- provider Ollama real implementado atrás de `LlmGateway`;
- JSON schema estrito com C1–C5, notas na escala 0/40/80/120/160/200 e evidências literais;
- verificação de versão, modelo, digest, quantização, contexto e término da geração;
- cache por configuração/revisão e apenas uma inferência local ativa;
- auditoria de provider, modelo, hashes, tempos, tokens e cache no SQLite;
- interface informa runtime, estado experimental, carregamento, revisão e erros;
- ciclo de treino com tentativas imutáveis, histórico, plano de melhoria e progresso;
- solicitação humana, fila do corretor, rascunho e publicação independente/versionada;
- dataset aceita apenas textos locais autorizados e rótulos humanos revisados;
- cinco sementes reais catalogadas somente como metadados de desenvolvimento.

Neste host, o smoke, a persistência após reinício e o E2E real passaram com Ollama 0.40.4 e Gemma 4 E2B/Q4_K_M. O runtime técnico está fixado por digest em [local-runtime-lock.json](eval/config/local-runtime-lock.json). Isso não aprova a qualidade das notas: a concordância contra corretores humanos ainda precisa ser medida com corpus autorizado. As evidências estão em [local-integration-status.md](eval/reports/local-integration-status.md).

## Subir o runtime real

Requisitos: Java 21, Maven, Docker Compose, `curl` e `jq`. O modelo ocupa vários gigabytes; os limites do Compose precisam ser conferidos no hardware real.

Na primeira instalação, crie o `.env` e disponibilize o tag local/customizado
`gemma4:e2b-it-q4_K_M` no volume do Ollama. O bootstrap não substitui esse modelo
por outro nem baixa vários gigabytes silenciosamente.

```bash
cp .env.example .env
docker-compose -f compose.ollama.yml up -d
# importe/crie gemma4:e2b-it-q4_K_M no Ollama conforme o lock do projeto
./scripts/ollama-smoke.sh
```

Depois, o comando normal de desenvolvimento é único:

```bash
mvn -Pdev-local spring-boot:run
```

O perfil `dev-local` chama `scripts/ollama-prerequisites.sh` durante a fase
`initialize`: aceita Compose v2 ou v1, sobe o serviço quando necessário, aguarda a
API local, exige o modelo Gemma exato com capacidade de completion e só então
inicia o Spring com `LLM_PROVIDER=ollama` e contexto 8192. Modelo ausente, Docker
inacessível ou timeout interrompem a inicialização antes do backend. Se o Ollama já
estiver saudável, o container atual é preservado.

Abra `http://localhost:8080/`. A imagem validada está fixada por digest.

Para desenvolvimento sem Ollama:

```bash
mvn spring-boot:run
```

Nesse modo `GET /api/runtime` mostrará `provider: fake`. Não trate o resultado como correção por LLM.

## Contrato principal

```http
GET /api/runtime
POST /api/essays
POST /api/essays/{id}/evaluate?profile=normal
GET /api/essays/{id}/result
GET /api/runs/{runId}
POST /api/essays/{id}/human-scores
POST /api/attempts
GET /api/essays/{id}
GET /api/me/essays
GET /api/me/essays/{groupId}/attempts
GET /api/me/progress
POST /api/essays/{id}/human-review-requests
GET /api/essays/{id}/human-review-request
GET /api/reviewer/review-requests
POST /api/reviewer/review-requests/{id}/claim
POST /api/reviewer/review-requests/{id}/draft
POST /api/reviewer/review-requests/{id}/publish
```

O frontend é uma SPA estática servida pelo Spring, sem build separado. O menu oferece
Nova redação, Minhas redações, Meu progresso e Correções pendentes. Neste piloto, o
aluno é a identidade fixa `pilot-student`. Rotas de corretor exigem `X-Reviewer-Key`
e usam `REVIEWER_KEY`; isso é uma barreira local, não autenticação para produção.

Uma reescrita é criada com `parentEssayId`. Avaliar ou solicitar correção humana
congela a revisão, impedindo sobrescrita. O corretor assume um pedido antes de salvar
ou publicar e não recebe a execução IA de referência até publicar sua avaliação.
Notas IA e humanas permanecem separadas, sem média automática.

`cheap`, `normal` e `premium` são aliases de compatibilidade; no runtime local atual não representam modelos diferentes nem ranking de qualidade. A avaliação síncrona tem timeout padrão de 600 s. O cliente pode acompanhar o último estado em `/result`.

Uma avaliação concluída contém `status`, `provider`, `model`, `competences`, `needsReview`, `reviewReasons`, `limitations`, `cacheHit` e `experimental`. Cada competência contém nota, nível sugerido, evidências, problemas e motivos. Falhas usam JSON com `status`, `error`, `code` e, quando criado, `runId`.

Principais códigos: `EVALUATION_BUSY`, `DOCUMENT_REVIEW_REQUIRED`, `STALE_REVISION`, `RUNTIME_UNAVAILABLE`, `MODEL_NOT_FOUND`, `RUNTIME_QUARANTINED`, `INFERENCE_TIMEOUT`, `INVALID_RESPONSE`, `TRUNCATED_RESPONSE` e `CONTEXT_LIMIT`.

## Dataset e notas humanas

O [manifesto](dataset/manifest.json) e o [catálogo de fontes](eval/real-essay-sources.json) guardam apenas URLs, metadados e notas humanas publicadas. Os textos completos do Brasil Escola e UOL não são copiados. Acesso público não comprova autorização para redistribuição, avaliação automatizada ou treino.

O projeto também possui um catálogo público separado para datasets com licença
aberta e notas humanas C1–C5. O importador do KAMeL-USP preserva `train`,
`validation` e `test`, deduplica por hash normalizado, guarda múltiplos corretores
e impede que amostras reservadas entrem na recuperação. Consulte
[dataset/PUBLIC-CORPUS.md](dataset/PUBLIC-CORPUS.md) para importar e auditar.

O código deste repositório é distribuído sob a licença [MIT](LICENSE). Cada
dataset mantém sua própria licença e proveniência; a licença do software não
substitui a licença do corpus.

## Testes E2E com Playwright

A suíte em `e2e/` usa Chromium isolado, locators acessíveis, execução paralela e relatório HTML. Em falhas, guarda screenshot e vídeo; no primeiro retry, guarda trace. O backend é iniciado automaticamente com banco SQLite isolado e provider fake em CI. Localmente, um servidor já aberto em `127.0.0.1:8080` é reutilizado.

Os cenários rápidos cobrem: envio digitado; validação obrigatória; upload e
conferência documental; avaliação e resultado C1–C5; falha de inferência sem nota;
pedido de correção humana; fila, aceite e publicação pelo corretor; comparação IA ×
humano; histórico e reescrita; plano de melhoria e checklist; progresso; e runtime
indisponível. A inferência real permanece separada em `@live`.

```bash
npm install
npx playwright install chromium
npm run e2e
```

Para assistir ao fluxo completo em velocidade reduzida — aluno, IA, pedido humano, área do corretor, publicação e comparação final:

```bash
npm run e2e:demo
```

Para conferir pela UI um resultado real já persistido, sem gerar outra correção:

```bash
E2E_LIVE_ESSAY_ID=719 npx playwright test e2e/live-ollama.spec.js --grep "resultado real"
```

O fluxo integral com inferência Ollama é opt-in, pode levar vários minutos e requer backend real já iniciado com `LLM_PROVIDER=ollama`:

```bash
npm run e2e:live
```

O caso autorizado de referência (800 pontos, 160 por competência) está em `eval/e2e-desinformacao-800.json`. O teste live aceita erro absoluto de até 80 pontos no total; esse limiar serve para detectar regressão neste caso, não para validar qualidade geral do corretor.

Para tornar uma amostra elegível, obtenha autorização compatível, salve o texto em `dataset/private/` (ignorado pelo Git), registre SHA-256 e evidência de direitos, revise a extração e os cinco rótulos humanos. Sementes já vistas durante o planejamento ficam em `dev`, nunca em teste cego.

```bash
mvn -q -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "target/classes:$(cat target/classpath.txt)" \
  com.redator.corretor.cli.EvalCli manifest-check --input dataset/manifest.json
```

O benchmark calcula métricas por competência, matriz de confusão, erro absoluto, viés e QWK somente quando a amostra é válida e há variação suficiente. Casos de fuga temática também devem medir a decisão de revisão/anulação. LoRA permanece fora do runtime; os pré-requisitos estão em [LORA-READINESS.md](eval/LORA-READINESS.md).

## Testes

```bash
mvn -q test
bash -n scripts/ollama-smoke.sh
bash -n scripts/ollama-prerequisites.sh
```

Os testes de Ollama usam um servidor HTTP local controlado: verificam exatamente uma chamada `/api/chat`, schema, opções, ausência de thinking e evidência literal, sem baixar modelo. O E2E real é opt-in (`LLM_LIVE_TEST=true mvn -q -Dtest=OllamaLiveIntegrationTest test`) e não aprova qualidade ENEM por si só.

## Arquitetura

```text
Browser -> Spring Boot -> EssayService -> LlmGateway -> fake | Ollama local
                    \-> TrainingCycleService -> tentativas/revisão humana/progresso
                    \-> SQLite/Flyway (run, competence_result, llm_call, human_review_request)
```

Rubrica, schema e prompts versionados ficam em `src/main/resources/{rubrics,schemas,prompts}`. A Cartilha do Participante ENEM 2026 local é a fonte normativa registrada por hash. O plano de implementação e os critérios de aceite estão em [plano_corretor_enem_ia_mvp_v2.md](plano_corretor_enem_ia_mvp_v2.md).

## Critério para dizer “pronto para uso”

O software está pronto para experimentação local individual: runtime e E2E real foram validados, e o ciclo de treino/humano possui testes de integração. Não está pronto para exposição multiusuária: faltam autenticação robusta, isolamento por dono e política operacional de corretores. Só estará pronto para apoiar decisões pedagógicas depois de benchmark autorizado, cego e revisado demonstrar concordância e limites aceitáveis. Até lá, toda saída permanece experimental.
