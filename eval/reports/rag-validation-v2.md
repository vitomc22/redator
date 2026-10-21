# Calibração do RAG em validation — 20/10/2026

Os três braços concluíram as mesmas três amostras como `READY`. O diagnóstico da variante **reprovou C4 e QWK agregado**. Houve melhora de MAE em algumas competências, mas ela não cumpriu o gate e permanece opt-in. A qualificação de release continua pendente.

## Protocolo e população

O corpus contém 66 redações em `validation`; somente três possuem médias humanas C1–C5 integralmente na escala 0/40/80/120/160/200 exigida pelo gate. As três foram congeladas antes da mudança e não compartilham hashes com o conjunto de `test`. A conferência do SQLite não encontrou nenhum `theme_group` atravessando splits no corpus atual.

- [População de validation](../frozen/public-validation-v1.json): seleção `f6430ef344ea4cdfd765f0b5c4e34da65b73dca1ba092bc7190022ff4a6f3af6`.
- Corpus lógico: `480b13dd5e8e0f6aaf54b1f38e35d296d6ff888682eaaefaca39ae1bb055cd57`.
- [Configuração e hashes dos fontes](../config/calibration-v2.json): fingerprint `648d83745c4ab140d6e9b5d233a2ea5ccf0f574055dd22e4e983314e0bd4417f`.
- [Baseline sem RAG](../results/validation-baseline-v1.json), executado com classes preservadas do commit `7f1663f`.
- [RAG antigo](../results/validation-rag-v1.json) e seu [diagnóstico de não regressão](../results/validation-gate-v1.json).
- [Variante calibration-v2](../results/validation-rag-v2.json) e seu [diagnóstico de não regressão](../results/validation-gate-v2.json).
- [Pré-verificação da variante](../results/validation-preflight-v2.json), sem geração.

Os braços usam bancos operacionais separados, o mesmo corpus público e o mesmo Gemma/digest/runtime/parâmetros do benchmark anterior: contexto 8192, saída máxima 1200, quatro threads, temperatura 0, seed 42 e thinking desativado. O RAG antigo usa as classes preservadas do commit `7f1663f`; a variante usa uma cópia das classes compiladas depois dos testes e do congelamento dos hashes.

## Hipótese e mudanças

O corte anterior mantinha apenas o começo de referências longas e apresentava a nota humana da redação inteira. Isso omitia conclusões relevantes para interpretar C5. A política opt-in `calibration-v2` conserva início e conclusão dentro de 1200 pontos de código Unicode, informa a cobertura do excerto e explicita que as notas são médias da redação inteira. Referências são tratadas como dados não confiáveis, sem autoridade para instruir o modelo.

Também foi corrigida a unidade do guard: entrada estimada por bytes UTF-8/3, saída e reserva de template precisam ser comparadas em tokens. A variante omite referências opcionais até caber, mantendo ao menos uma; se não couber, falha antes da inferência. O texto do estudante não é truncado. Política, hash das mensagens, hashes dos excertos e quantidade de exemplos omitidos entram na auditoria e no fingerprint do cache.

Na pré-verificação das três amostras, a variante selecionou respectivamente 3, 3 e 2 referências. As estimativas de entrada foram 6478, 6592 e 6256 tokens; somadas aos 1200 de saída e 256 de reserva, ficam dentro de 8192. Essa verificação mede orçamento e preparação do pedido, não qualidade da correção. O guard anterior aceitou a terceira amostra com três referências; a nova estimativa conservadora exigiu reduzir uma delas. Portanto, a variante combina apresentação de excertos e adaptação da quantidade de referências; o experimento não isola causalmente cada fator.

## Resultado medido

MAE em pontos por competência, na escala 0–200, sobre a mesma população completa:

| Competência | Sem RAG | RAG lexical-v1 | RAG calibration-v2 |
| --- | ---: | ---: | ---: |
| C1 | 13,3333 | 13,3333 | 0,0000 |
| C2 | 66,6667 | 80,0000 | 53,3333 |
| C3 | 80,0000 | 80,0000 | 80,0000 |
| C4 | 13,3333 | 26,6667 | 26,6667 |
| C5 | 80,0000 | 66,6667 | 66,6667 |

QWK agregado: **0,072398** sem RAG, **0,046243** com `lexical-v1` e **−0,069470** com `calibration-v2`.

O RAG antigo reprovou C2, C4 e QWK. A variante reprovou C4 e QWK: reduzir erro absoluto em C1 e C2 não bastou para cumprir os critérios de não regressão por competência e de concordância agregada. As notas, amostras e limiares foram preservados; nenhuma execução foi descartada.

As nove avaliações registraram uma chamada real por amostra, sem cache reutilizado e com término `stop`. A auditoria confirmou digest, runtime, contrato, parâmetros e hash do texto integral consistentes entre braços. O RAG antigo usou nove referências e a variante usou oito; todas foram conferidas no SQLite como exemplos de `train`, com hashes correspondentes aos IDs e fora da população reservada. A variante registrou contagens 3/3/2 e omissões 0/0/1, respeitando entrada estimada + saída + reserva em todas as amostras.

As latências registradas somaram 795,081 s, 1052,824 s e 858,073 s nos três braços, respectivamente. A carga da máquina não foi controlada; são observações desta execução.

## Gate de distribuição

O perfil `mvn -Prelease-quality verify` executa o gate em `prepare-package`. Além das métricas, exige `test`, Ollama/Gemma, política correspondente, chamadas sem cache, hashes do contrato/texto/schema e identidade do modelo/runtime/parâmetros consistentes. Ele rejeita hashes do benchmark nas referências, população incompleta, notas fracionárias e corpus diferente do congelado.

O empacotamento foi exercitado com os artefatos reais de `test-v1` e bloqueado pelas regressões de C2, C5 e QWK já registradas. A [reprovação anterior](rag-public-test-v1.md) permanece preservada. `validation` não é aceito como evidência de release.

## Verificação funcional

- Java, em sequência: 82 testes unitários aprovados; 32 testes de integração, com 31 aprovados e um live opt-in ignorado.
- Configuração Playwright: três testes Node aprovados, cobrindo modo offline, live com SQLite novo e inspeção explícita de servidor externo.
- Playwright offline: 20 testes aprovados após a última mudança funcional.
- Playwright com Ollama/Gemma e SQLite reais: fluxo funcional aprovado em 5,9 minutos com `calibration-v2`; o cenário manual que exige `E2E_LIVE_ESSAY_ID` ficou ignorado. Banco novo, uma chamada real sem cache e duas referências auditadas.
- Java live com `lexical-v1` e banco novo: aprovado em 233,8 s depois do navegador, com uma geração real sem cache. [Auditoria](../results/default-live-v1.json).
- Playwright de qualidade: `npm run e2e:quality` com `calibration-v2` reprovou em 2,4 minutos pelo erro de 200 pontos, acima da tolerância histórica de 80. As verificações funcionais executadas antes dessa asserção passaram. [Execução em outra coleção](../results/browser-reference-quality-v2.json).
- Build de desenvolvimento: `mvn -q -DskipTests package` aprovado após as verificações; JAR SHA-256 `f35e764ec867713e78b928eed2fcbccf808faad6dd235db238b600f40e534486`. Os 114 testes Java distintos passaram, incluindo o live opt-in executado separadamente.
- O SBOM é gerado como arquivo separado em `target/classes/META-INF/sbom/application.cdx.json`, após o empacotamento. Foi conferido como CycloneDX 1.6, com 54 componentes, SQLite presente e dependências JUnit ausentes; uma cópia foi colocada ao lado do JAR em `target/corretor-enem-0.1.0-SNAPSHOT.sbom.json`. O JAR foi conferido contra as classes compiladas atuais.

A configuração live deixou de iniciar o provider `fake`. Cada execução normal live abre uma coleção SQLite nova e não reutiliza um servidor existente. O modo funcional verifica publicação, notas na escala oficial, evidências literais inteiras e auditoria persistida. O modo separado `npm run e2e:quality` mantém a tolerância histórica de 80 pontos para a referência humana autorizada da fixture e anexa seu diagnóstico.

O fluxo funcional publicou **1000**, enquanto a referência humana histórica é **800**: erro absoluto de **200**, acima da tolerância. Esse resultado foi preservado no [diagnóstico da referência](../results/browser-reference-v2.json). A execução separada do modo de qualidade, em outra coleção nova, também publicou 1000 e reprovou pela mesma diferença. As duas observações permanecem registradas. A aprovação do contrato não implica aprovação da nota.

## Limite da conclusão

Três amostras e uma execução por braço permitem diagnosticar a variante, sem demonstrar qualidade pedagógica geral nem estimar variabilidade de geração. O padrão permanece `lexical-v1`, e o benchmark `test-v1` e sua reprovação não foram sobrescritos.

A investigação posterior identificou que a restrição por médias, e não a ausência de notas humanas válidas, limitava esta população. O [protocolo de dois avaliadores](two-raters-v2.md) permite usar as 66 redações de `validation` com suas 132 anotações originais, calculando métricas separadas por humano antes da média. Os resultados v1 acima preservam sua população e método originais; não foram recalculados como se pertencessem ao novo protocolo.
