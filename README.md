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
- dataset aceita apenas textos locais autorizados e rótulos humanos revisados;
- cinco sementes reais catalogadas somente como metadados de desenvolvimento.

Neste host, Docker, Ollama e a porta 11434 não estavam disponíveis. Portanto, os testes offline passam, mas a inferência real do Gemma e sua qualidade contra humanos ainda não foram medidas. O registro está em [local-integration-status.md](eval/reports/local-integration-status.md), e o lock permanece corretamente `pending` em [local-runtime-lock.json](eval/config/local-runtime-lock.json).

## Subir o runtime real

Requisitos: Java 21, Maven, Docker Compose, `curl` e `jq`. O modelo ocupa vários gigabytes; os limites do Compose precisam ser conferidos no hardware real.

```bash
cp .env.example .env
docker compose -f compose.ollama.yml up -d
docker compose -f compose.ollama.yml exec ollama ollama pull gemma4:e2b-it-q4_K_M
./scripts/ollama-smoke.sh
set -a; source .env; set +a
LLM_PROVIDER=ollama mvn spring-boot:run
```

Abra `http://localhost:8080/`. O pull é explícito: a aplicação não baixa modelos e não promove automaticamente a imagem `latest` para lock aprovado. Depois do smoke, registre os digests reais e valide reinício, memória, swap e percurso API → modelo → persistência antes de mudar o lock.

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
```

`cheap`, `normal` e `premium` são aliases de compatibilidade; no runtime local atual não representam modelos diferentes nem ranking de qualidade. A avaliação síncrona tem timeout padrão de 600 s. O cliente pode acompanhar o último estado em `/result`.

Uma avaliação concluída contém `status`, `provider`, `model`, `competences`, `needsReview`, `reviewReasons`, `limitations`, `cacheHit` e `experimental`. Cada competência contém nota, nível sugerido, evidências, problemas e motivos. Falhas usam JSON com `status`, `error`, `code` e, quando criado, `runId`.

Principais códigos: `EVALUATION_BUSY`, `DOCUMENT_REVIEW_REQUIRED`, `STALE_REVISION`, `RUNTIME_UNAVAILABLE`, `MODEL_NOT_FOUND`, `RUNTIME_QUARANTINED`, `INFERENCE_TIMEOUT`, `INVALID_RESPONSE`, `TRUNCATED_RESPONSE` e `CONTEXT_LIMIT`.

## Dataset e notas humanas

O [manifesto](dataset/manifest.json) e o [catálogo de fontes](eval/real-essay-sources.json) guardam apenas URLs, metadados e notas humanas publicadas. Os textos completos do Brasil Escola e UOL não são copiados. Acesso público não comprova autorização para redistribuição, avaliação automatizada ou treino.

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
```

Os testes de Ollama usam um servidor HTTP local controlado: verificam exatamente uma chamada `/api/chat`, schema, opções, ausência de thinking e evidência literal, sem baixar modelo. O smoke real é opt-in e não aprova qualidade ENEM por si só.

## Arquitetura

```text
Browser -> Spring Boot -> EssayService -> LlmGateway -> fake | Ollama local
                    \-> SQLite/Flyway (run, competence_result, llm_call)
```

Rubrica, schema e prompts versionados ficam em `src/main/resources/{rubrics,schemas,prompts}`. A Cartilha do Participante ENEM 2026 local é a fonte normativa registrada por hash. O plano de implementação e os critérios de aceite estão em [plano_corretor_enem_ia_mvp_v2.md](plano_corretor_enem_ia_mvp_v2.md).

## Critério para dizer “pronto para uso”

O software está pronto para experimentação local quando o smoke e o E2E real passarem no hardware alvo. Ele só estará pronto para apoiar notas humanas depois de um benchmark autorizado, cego e revisado demonstrar concordância por competência e limites aceitáveis de revisão, erro, estabilidade e latência. Até lá, toda saída deve permanecer marcada como experimental.
