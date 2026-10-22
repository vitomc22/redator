# Corretor ENEM IA — MVP experimental

Monólito Java 21/Spring Boot para corrigir redações segundo as cinco competências do ENEM. O runtime normal usa Ollama local com `gemma4:e2b-it-q4_K_M`, RAG com redações públicas corrigidas por humanos, uma única geração estruturada por redação e auditoria persistida. O provider `fake` existe exclusivamente para testes offline; suas notas não medem qualidade pedagógica.

O sistema não substitui a correção oficial. Uma nota só é publicada quando a resposta passa pelo schema e não exige revisão; `RUNNING`, `FAILED`, `STALE` e `NEEDS_REVIEW` retornam `total: null`.

## Estado verificável

- provider Ollama real implementado atrás de `LlmGateway`;
- JSON schema estrito com C1–C5, notas na escala 0/40/80/120/160/200 e evidências literais;
- verificação de versão, modelo, digest, quantização, contexto e término da geração;
- cache por configuração/revisão e apenas uma inferência local ativa;
- auditoria de provider, modelo, hashes, tempos, tokens e cache no SQLite;
- RAG ativo no fluxo normal, limitado ao split `train`, com exclusão da redação atual, fingerprint do corpus e referências auditáveis;
- interface informa runtime, estado experimental, carregamento, revisão e erros;
- ciclo de treino com tentativas imutáveis, histórico, plano de melhoria e progresso;
- solicitação humana, fila do corretor, rascunho e publicação independente/versionada;
- dataset aceita apenas textos locais autorizados e rótulos humanos revisados;
- cinco sementes reais catalogadas somente como metadados de desenvolvimento.

Neste host, o smoke, a persistência após reinício e o E2E real passaram com Ollama 0.40.4 e Gemma 4 E2B/Q4_K_M. O runtime técnico está fixado por digest em [local-runtime-lock.json](eval/config/local-runtime-lock.json). Isso não aprova a qualidade das notas: a concordância contra corretores humanos ainda precisa ser medida com corpus autorizado. As evidências estão em [local-integration-status.md](eval/reports/local-integration-status.md).

## Instalar e iniciar localmente

O launcher prepara um diretório privado por usuário, escolhe uma porta livre somente
em `127.0.0.1`, valida Java 21, instala ou encontra o Ollama nativo, inicia/verifica
Ollama + Gemma, aguarda a interface
ficar pronta e mantém PID, porta e logs fora do repositório:

```bash
./scripts/redator-launch.sh check
./scripts/redator-launch.sh start
./scripts/redator-launch.sh status
./scripts/redator-launch.sh stop
```

Use `run` no lugar de `start` para manter o backend em primeiro plano. No Linux, os
dados ficam por padrão em `${XDG_DATA_HOME:-$HOME/.local/share}/redator-enem`; no
macOS, em `$HOME/Library/Application Support/Redator ENEM`. `REDATOR_DATA_DIR` e
`REDATOR_PORT` permitem escolha explícita sem expor o serviço na rede.

O bootstrap usa o instalador oficial do Ollama por HTTPS, confere seu SHA-256 fixado
antes de executá-lo e fixa a versão aprovada. Se o arquivo oficial mudar, a instalação
é interrompida para revisão em vez de executar conteúdo novo silenciosamente. Defina
`OLLAMA_AUTO_INSTALL=false` para apenas diagnosticar uma instalação existente;
`OLLAMA_RUNTIME=docker` mantém o Compose como opção avançada explícita.

### Desenvolvimento do runtime real

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
API local, exige o corpus RAG não vazio e o modelo Gemma exato com capacidade de completion e só então
inicia o Spring com `LLM_PROVIDER=ollama` e contexto 8192. Modelo ausente, Docker
inacessível ou timeout interrompem a inicialização antes do backend. Se o Ollama já
estiver saudável, o container atual é preservado.

Abra `http://localhost:8080/`. A imagem validada está fixada por digest.

Para executar deliberadamente a suíte sem Ollama:

```bash
LLM_PROVIDER=fake mvn spring-boot:run
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
Nova redação, Minhas redações, Meu progresso e Correções pendentes. Cada navegador
usa um identificador local enviado em `X-Local-User`; ele pode ser trocado pelo avatar
e isola o histórico sem simular autenticação. As rotas do corretor não exigem chave no
modo local. Portanto, o servidor deve permanecer acessível apenas em loopback.

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

Na correção Ollama, até três exemplos elegíveis são recuperados do corpus por tema e
termos do texto. Eles entram no prompt somente como calibração da rubrica; o modelo é
instruído a não copiar nota, frase ou justificativa. Splits de validação/teste e o hash
da própria redação não podem ser recuperados. Corpus ausente ou sem amostras elegíveis
interrompe a inicialização, em vez de executar silenciosamente sem RAG.
As duas políticas reduzem as referências de menor relevância quando necessário para
reservar o contexto da saída, preservando a redação integral e pelo menos um exemplo.
A quantidade omitida fica na auditoria. Se o conteúdo obrigatório ainda não couber,
o aplicativo retorna `CONTEXT_LIMIT` sem chamar o modelo.

O código deste repositório é distribuído sob a licença [MIT](LICENSE). Cada
dataset mantém sua própria licença e proveniência; a licença do software não
substitui a licença do corpus.

## Coleções privadas por perfil

O modo padrão grava cada coleção em `profiles/<perfil>/redator.sqlite`, dentro do
diretório de dados. Redações, correções, cache e treino ficam no banco do perfil
ativo. A exportação em **Dados locais** contém somente essa coleção; a restauração
recusa outro proprietário, schemas desconhecidos e relações inválidas, e é aplicada
após reiniciar, com snapshot de segurança da coleção anterior.

Quando existe `redator.db` legado, a primeira inicialização prepara e ativa a
separação automaticamente. A origem e um snapshot completo são preservados, com
manifesto e hashes em `runtime/profile-migration-prepared`. Uma colisão ou alteração
do legado interrompe a ativação sem sobrescrever coleções privadas existentes.
O modo de compatibilidade pode ser selecionado com `REDATOR_PROFILE_DATABASES=false`;
ele usa apenas o legado e deve ser usado sem alternar gravações entre os dois modos.
A [ferramenta offline](eval/reports/profile-collections-v1.md) continua disponível
para inspecionar a separação antes da ativação. Veja também a
[validação do modo ativo](eval/reports/profile-databases-v1.md).

## Testes E2E com Playwright

A suíte em `e2e/` usa Chromium isolado, locators acessíveis, execução paralela e relatório HTML. Em falhas, guarda screenshot, vídeo e trace. Cada execução inicia seu próprio backend em `127.0.0.1:18080`, com diretório e SQLite novos; um servidor já aberto não é reutilizado. A suíte rápida usa provider fake: seus resultados são simulação e não publicam notas de IA.

Os cenários rápidos cobrem: envio digitado; validação obrigatória; upload e
conferência documental; avaliação e resultado C1–C5; falha de inferência sem nota;
pedido de correção humana; fila, aceite e publicação pelo corretor; comparação IA ×
humano; histórico e reescrita; plano de melhoria e checklist; progresso; e runtime
indisponível. Os testes com APIs interceptadas verificam a interface. Fluxos adicionais sem interceptação verificam Spring e SQLite reais, incluindo PDF comprimido, conferência obrigatória, fila local do professor, rascunho/retomada/publicação, importação cifrada e progresso humano sem IA. A inferência real permanece separada em `@live`.

```bash
npm install
npx playwright install chromium
npm run test:config
npm run test:frontend
npm run e2e
npm run e2e:failure
```

`e2e:failure` usa o provider Ollama real contra uma porta local inacessível, em outro SQLite novo. Verifica HTTP 503, ausência de notas e persistência do pedido humano após recarregar. Apenas essa configuração de falha desativa o reparo automático do runtime. Não há interceptação de API nesse teste.

Para assistir ao fluxo completo em velocidade reduzida — aluno, IA, pedido humano, área do corretor, publicação e comparação final:

```bash
npm run e2e:demo
```

Para conferir pela UI um resultado real já persistido, sem gerar outra correção:

```bash
PLAYWRIGHT_EXTERNAL_SERVER=1 E2E_LIVE_ESSAY_ID=719 npx playwright test e2e/live-ollama.spec.js --grep "resultado real"
```

O fluxo integral com inferência Ollama é opt-in, pode levar vários minutos e inicia seu próprio backend Ollama com SQLite novo. Requer o runtime e o corpus configurados:

```bash
npm run e2e:live
```

O caso autorizado de referência (800 pontos, 160 por competência) está em `eval/e2e-desinformacao-800.json`. `e2e:live` verifica o funcionamento; `npm run e2e:quality` exige adicionalmente erro absoluto de até 80 pontos no total. A execução registrada produziu 1000 e reprovou essa tolerância. Esse limiar de uma amostra não valida qualidade geral do corretor.

Para tornar uma amostra elegível, obtenha autorização compatível, salve o texto em `dataset/private/` (ignorado pelo Git), registre SHA-256 e evidência de direitos, revise a extração e os cinco rótulos humanos. Sementes já vistas durante o planejamento ficam em `dev`, nunca em teste cego.

```bash
mvn -q -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "target/classes:$(cat target/classpath.txt)" \
  com.redator.corretor.cli.EvalCli manifest-check --input dataset/manifest.json
```

O benchmark calcula métricas por competência, matriz de confusão, erro absoluto, viés e QWK somente quando a amostra é válida e há variação suficiente. Casos de fuga temática também devem medir a decisão de revisão/anulação. LoRA permanece fora do runtime; os pré-requisitos estão em [LORA-READINESS.md](eval/LORA-READINESS.md).

O benchmark público congelado é exportado somente dos splits reservados, em ordem
determinística, com hashes de cada redação, fingerprint lógico do corpus e fingerprint
da seleção. O exportador legado v1 só admite médias humanas integralmente na escala
oficial C1–C5 (0/40/80/120/160/200), sem arredondar rótulos intermediários. Isso
restringe a população mesmo quando as duas notas individuais são válidas. O artefato
histórico v1 contém 11 redações do split `test` e permanece preservado:

```bash
java -cp "target/classes:$(cat target/classpath.txt)" \
  com.redator.corretor.cli.FrozenBenchmarkCli \
  --database dataset/public-corpus.db --split test --limit 100 \
  --output eval/frozen/public-test-v1.json
```

Isso congela a população e os rótulos, mas não aprova o RAG sozinho. A aprovação exige
predições pareadas do mesmo Gemma/configuração com RAG desligado e ligado. Cada braço
usa um banco operacional isolado, grava progresso a cada amostra e registra configuração,
auditoria e hashes:

```bash
java -cp "target/classes:$(cat target/classpath.txt)" \
  com.redator.corretor.cli.PairedBenchmarkCli \
  --benchmark eval/frozen/public-test-v1.json --arm baseline \
  --data-directory target/benchmark-baseline --output eval/results/baseline-v1.json

java -cp "target/classes:$(cat target/classpath.txt)" \
  com.redator.corretor.cli.PairedBenchmarkCli \
  --benchmark eval/frozen/public-test-v1.json --arm rag \
  --data-directory target/benchmark-rag --output eval/results/rag-v1.json

java -cp "target/classes:$(cat target/classpath.txt)" \
  com.redator.corretor.cli.RagQualityGateCli \
  eval/frozen/public-test-v1.json eval/results/baseline-v1.json \
  eval/results/rag-v1.json eval/results/gate-v1.json
```

O gate identifica amostras pelo hash, recalcula a impressão do benchmark, rejeita
duplicatas/cobertura parcial e exige provider, modelo e parâmetros idênticos. Ele usa
a escala correta de seis níveis por competência e reprova QWK não calculável.

Os dois braços versionados concluíram 11/11 avaliações `READY`. O gate de
20/10/2026 **reprovou o RAG**: MAE de C2 passou de 76,3636 para 80,0000, MAE de
C5 de 80,0000 para 94,5455 e QWK agregado de 0,126394 para 0,031611. A qualidade
do RAG permanece experimental e sua qualificação está pendente. Veja o
[relatório completo e os artefatos](eval/reports/rag-public-test-v1.md).

A política `calibration-v2` é opt-in para calibração: conserva início e conclusão
das referências dentro de 1200 pontos de código, identifica as notas como médias
da redação inteira e reduz a quantidade de exemplos quando necessário para
reservar contexto para a saída. O texto do estudante é preservado. Política,
hash das mensagens, hashes dos excertos e exemplos omitidos ficam na auditoria.
O padrão continua `lexical-v1`; a configuração experimental está congelada em
[calibration-v2.json](eval/config/calibration-v2.json).

O conjunto legado de `validation` contém três amostras cujas médias satisfazem a
restrição v1. Ele serve para diagnóstico de calibração e não qualifica um release:

```bash
RAG_POLICY=calibration-v2 java -cp "target/classes:$(cat target/classpath.txt)" \
  com.redator.corretor.cli.PairedBenchmarkCli \
  --benchmark eval/frozen/public-validation-v1.json --arm rag \
  --data-directory target/validation-calibration-new-run \
  --output target/validation-calibration-new-run.json
```

O protocolo v2 preserva **as duas correções humanas individuais**. Calcula MAE e
QWK separadamente por avaliador e tira a média das duas métricas; não arredonda a
média das notas nem calcula um único QWK juntando os avaliadores. Um QWK agregado
indefinido em qualquer avaliador reprova o gate. Assim, as 66 redações de validação
podem ser usadas, com suas 132 anotações originais. O novo gate também informa a
concordância entre os humanos e conserva os mesmos controles de auditoria de release.

```bash
java -cp "target/classes:$(cat target/classpath.txt)" \
  com.redator.corretor.cli.TwoRaterBenchmarkCli \
  --database dataset/public-corpus.db --split validation \
  --output eval/frozen/public-validation-two-raters-v2.json

java -cp "target/classes:$(cat target/classpath.txt)" \
  com.redator.corretor.cli.TwoRaterBenchmarkCli \
  --database dataset/public-corpus.db --split test \
  --exclude-benchmark eval/frozen/public-test-v1.json \
  --output eval/frozen/public-test-unscored-two-raters-v2.json
```

A segunda seleção reserva as outras 58 redações de `test`, com 116 anotações;
elas não pertencem ao `test-v1`. Compartilham a partição temática de teste já
conhecida, portanto não representam uma nova coleta temática independente.
O exportador verifica a impressão da seleção excluída e recusa substituir um
arquivo congelado por uma população diferente. `PairedBenchmarkCli` aceita os
dois formatos, e `RagQualityGateCli` escolhe o protocolo pela versão do benchmark.
Veja o [protocolo e piloto com dois avaliadores](eval/reports/two-raters-v2.md).

Para experimentos com ordem de chaves JSON estável entre processos Java, habilite
`APP_LLM_STABLE_JSON=true` nos dois braços. Esse modo ordena mapas recursivamente,
preserva a ordem das listas e os trechos literais, e recebe outro `promptHash` para
separar auditoria e cache. O modo padrão e os experimentos congelados continuam
com o contrato legado. O modo estável exige evidência própria; seu hash é
rejeitado pelo gate de release do contrato legado.
Veja a [verificação da ordenação estável](eval/reports/stable-json-v1.md).

O contrato `APP_LLM_CONTRACT=grounded-v2` é outra opção experimental: distribui
evidências pela redação inteira e explicita distinções de C2/C5. No
[piloto com três redações novas](eval/reports/grounded-v3.md), o QWK agregado
melhorou, mas o RAG ainda regrediu em C5. Essa variante permanece opt-in e não
qualifica release.

Para empacotar artefatos destinados à distribuição, execute:

```bash
mvn -Prelease-quality verify
```

O perfil executa o gate em `prepare-package` e bloqueia o empacotamento se houver
reprovação. Ele exige `test`, Ollama/Gemma, a política esperada e auditorias
compatíveis com o contrato atual, além de cobertura completa e métricas sem
regressão. Os artefatos medidos atuais bloqueiam esse comando por C2, C5 e QWK.
Os caminhos podem ser definidos por `rag.release.benchmark`,
`rag.release.baseline` e `rag.release.candidate`; `rag.release.policy` identifica
a política que o release pretende qualificar (padrão `lexical-v1`).

A [calibração em validation](eval/reports/rag-validation-v2.md) concluiu os três
braços com 3/3 `READY`. A variante melhorou MAE em C1 e C2, mas reprovou C4 e QWK
agregado; segue opt-in, sem promoção do padrão.

## Testes

```bash
mvn -q test
bash -n scripts/ollama-smoke.sh
bash -n scripts/ollama-prerequisites.sh
bash -n scripts/redator-launch.sh
```

Os testes de Ollama usam SQLite real para o corpus e um servidor HTTP local controlado:
verificam recuperação sem vazamento de split, exclusão da própria redação, fingerprint,
injeção das notas humanas no prompt, auditoria, exatamente uma chamada `/api/chat`,
schema, opções, ausência de thinking e evidência literal. O E2E real é opt-in
(`LLM_LIVE_TEST=true mvn -q -Dtest=OllamaLiveIntegrationTest test`) e não aprova qualidade ENEM por si só.

`npm run test:config` verifica os modos do Playwright. `npm run e2e:live` inicia
o backend configurado para Ollama com uma coleção SQLite nova por execução, sem reutilizar um servidor
existente, e verifica publicação, evidências literais e auditoria persistida.
Para exercitar a variante, use `RAG_POLICY=calibration-v2 npm run e2e:live`.
A suíte funcional valida o contrato e registra a comparação com a referência
humana autorizada da fixture. `npm run e2e:quality` preserva a checagem histórica
de qualidade, com tolerância de 80 pontos; pode reprovar mesmo quando publicação
e persistência funcionam. O [diagnóstico observado](eval/results/browser-reference-v2.json)
registrou 1000 contra referência 800, erro de 200 pontos.

Após gerar o pacote de desenvolvimento com `mvn -q -DskipTests package`, execute
`npm run test:package` para conferir o SBOM CycloneDX e as classes/configurações
incluídas no JAR. Essa inspeção também é executada no CI; a qualificação das notas
continua sendo responsabilidade do gate `release-quality`.

Para inspecionar uma correção humana já preparada em um servidor local, use
`PLAYWRIGHT_EXTERNAL_SERVER=1`, `PLAYWRIGHT_BASE_URL` e `E2E_LIVE_ESSAY_ID` com
`npx playwright test --grep 'referência humana independentes'`. Esse modo
explícito usa o servidor informado e não inicia outro backend.

## Arquitetura

```text
Browser -> Spring Boot -> EssayService -> LlmGateway -> Ollama local
                                           \-> RagService -> corpus público SQLite
                    \-> TrainingCycleService -> tentativas/revisão humana/progresso
                    \-> SQLite/Flyway (run, competence_result, llm_call, human_review_request)
```

Rubrica, schema e prompts versionados ficam em `src/main/resources/{rubrics,schemas,prompts}`. A Cartilha do Participante ENEM 2026 local é a fonte normativa registrada por hash. O plano de implementação e os critérios de aceite estão em [plano_redator_local_first.md](plano_redator_local_first.md), com o progresso em [implementacao_local_first.md](docs/implementacao_local_first.md).

## Critério para dizer “pronto para uso”

O software está pronto para uso e experimentação local: runtime, RAG, ciclo de treino,
correção humana e fluxos E2E têm cobertura automatizada. Não está pronto para exposição
em rede: por decisão atual não há autenticação, e o identificador local não é uma
credencial. A correção automática continua identificada como estimativa experimental
até que um benchmark humano, cego e revisado demonstre concordância aceitável.
