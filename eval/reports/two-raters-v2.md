# Protocolo com dois avaliadores humanos — 20/10/2026

O protocolo novo preserva as notas individuais dos dois corretores e permite usar todas as 66 redações de validação já importadas, com 132 anotações. Não exige nova coleta nem arredonda notas. Os experimentos anteriores e suas reprovações permanecem preservados.

## Justificativa

O corpus local JBCS2025 contém 385 redações: 250 em `train`, 66 em `validation` e 69 em `test`. Cada redação tem duas avaliações humanas verificadas (`grader_a` e `grader_b`), cujas cinco notas individuais pertencem à escala oficial 0/40/80/120/160/200. O exportador legado conservava somente redações em que todas as médias também pertenciam a essa escala: três em validação e onze em teste. Uma discordância válida como 120 e 160 produzia média 140 e eliminava a redação do benchmark.

A [dissertação do autor, seções 4.2 e 4.4.2](https://www.teses.usp.br/teses/disponiveis/45/45134/tde-25092025-141609/publico/MScThesisAESAndreBarbosaReviewed.pdf) descreve duas correções humanas especializadas e avaliação com métricas obtidas contra os dois avaliadores. O protocolo deste projeto explicita a média das métricas por avaliador, mantendo os rótulos individuais originais.

## Seleções congeladas

Todas mantêm o corpus lógico `480b13dd5e8e0f6aaf54b1f38e35d296d6ff888682eaaefaca39ae1bb055cd57`.

| Seleção | Redações | Anotações | Fingerprint da seleção |
| --- | ---: | ---: | --- |
| [Validação completa](../frozen/public-validation-two-raters-v2.json) | 66 | 132 | `be792009d7226c17fd9167b09d76f45f8d4bc9ef11c7e8da07af6fb5503fc2a5` |
| [Teste adicional reservado](../frozen/public-test-unscored-two-raters-v2.json) | 58 | 116 | `97064296333f9ab5654432764acd838927499ae5dd28df3022cd70f696bc09eb` |
| [Piloto de integração em validação](../frozen/public-validation-two-raters-pilot-v2.json) | 3 | 6 | `00bff50acd7944f29cbaa1f9e5a3b2b1d59b1f41f9c77dbb01019a055ae43a45` |

O teste adicional exclui os onze hashes de `test-v1`; não foi usado para inferência nesta etapa. Compartilha a mesma partição temática de teste já conhecida e não constitui nova coleta temática independente. O piloto seleciona os três primeiros hashes de validação após excluir as três redações já avaliadas em `validation-v1`. A seleção e o código foram congelados antes da inferência; não dependem do desempenho do modelo.

O exportador abre a consulta de anotações em modo somente leitura, confere o fingerprint lógico antes e depois, valida dois corretores distintos e consistentes, níveis oficiais, soma do total e integridade do texto. A exclusão confere corpus, split e impressão da seleção anterior. Um arquivo congelado existente só é aceito se seu conteúdo for idêntico ao exportado.

## Métricas e gate

O identificador do método é `individual-raters-macro-average-v1`, em benchmarks `schemaVersion: 2`. Para cada humano, calculam-se as métricas com exatamente uma predição por redação. Depois:

- o MAE de cada competência é a média dos dois MAEs;
- o QWK de cada competência é a média dos dois QWKs;
- o QWK agregado concatena C1–C5 dentro de cada avaliador e só então tira a média dos dois QWKs agregados;
- não há média de notas humanas, arredondamento de rótulos nem QWK único juntando as duas referências;
- QWK agregado indefinido de um avaliador não pode ser descartado da média: reprova o gate;
- exige-se ausência de regressão no MAE das cinco competências e no QWK agregado médio.

O relatório também preserva os dois resultados individuais e a concordância humano–humano. A contagem agregada desta concordância corresponde a cinco pares de notas por redação, não a cinco redações independentes. Ela é um diagnóstico, não um limiar escolhido para aprovar a IA.

A [concordância humana nas 66 redações de validação](../results/validation-human-agreement-v2.json), calculada sem inferência com o mesmo calculador de métricas do aplicativo, foi:

| Competência | MAE entre humanos | QWK entre humanos |
| --- | ---: | ---: |
| C1 | 22,4242 | 0,525543 |
| C2 | 32,1212 | 0,521739 |
| C3 | 30,9091 | 0,563877 |
| C4 | 23,6364 | 0,385542 |
| C5 | 35,7576 | 0,549801 |

No conjunto de 330 pares de notas C1–C5, o MAE foi 28,9697, o QWK 0,618336 e a concordância exata 45,76%; 86,06% dos pares ficaram a até um nível oficial de distância. A unidade amostral continua sendo a redação (66), e os critérios do gate não foram ajustados a essas observações.

Ambos os protocolos usam os mesmos controles de cobertura por hash, configuração, corpus e auditoria. A opção `--release` continua exigindo `test`, Ollama/Gemma, uma chamada sem cache por redação, identidade de modelo/runtime e contrato atuais e ausência dos hashes reservados nas referências. Validação continua sem autoridade para qualificar release.

## Preparação sem inferência

A [preparação das 66 redações](../results/validation-two-raters-preflight-v2.json) passou nos dois modos, sem chamar `/api/chat`. No baseline, a maior estimativa de entrada foi 5114 tokens; na variante, 6726. Com 1200 de saída e 256 de reserva, todas cabem em 8192. A variante omitiu 74 referências opcionais no conjunto, preservando pelo menos uma por redação e todo o texto avaliado.

Isso verifica formação do pedido, modelo disponível, hashes e orçamento estimado. Não mede qualidade de notas nem prova a contagem exata de tokens do runtime.

## Piloto real

A [configuração congelada](../config/two-raters-pilot-v2.json) registra hashes dos fontes e classes, parâmetros e seleção. A geração usa o mesmo código de inferência da variante anterior, Gemma `gemma4:e2b-it-q4_K_M`, digest `b37049369adfe3d2b653af0ab301a062ca5cbe96ace6aa0d3f2559ee9b563fc2`, contexto 8192, saída máxima 1200, quatro threads, temperatura zero, seed 42 e thinking desativado. Cada braço usa um SQLite operacional novo.

O objetivo do piloto é exercitar a integração do novo protocolo com geração real. Três redações não sustentam uma estimativa geral de qualidade sobre as 66 redações. A execução completa da validação e a qualificação de release permanecem etapas distintas.

Os dois braços concluíram 3/3 `READY`: [baseline](../results/two-raters-pilot-baseline-v2.json), [RAG](../results/two-raters-pilot-rag-v2.json) e [gate](../results/two-raters-pilot-gate-v2.json). O piloto **reprovou C3, C5 e QWK agregado médio**:

| Competência | MAE médio sem RAG | MAE médio com RAG |
| --- | ---: | ---: |
| C1 | 13,3333 | 13,3333 |
| C2 | 26,6667 | 26,6667 |
| C3 | 26,6667 | 40,0000 |
| C4 | 20,0000 | 20,0000 |
| C5 | 46,6667 | 73,3333 |

O QWK agregado médio passou de aproximadamente zero (`−1,39 × 10⁻¹⁵`, resíduo numérico) para `−0,022351`. O QWK individual de C1 não é calculável neste piloto pequeno e permanece explicitamente indefinido; não foi substituído por uma concordância artificial. O gate usa MAE por competência e QWK agregado médio, conforme o protocolo fixado.

A [conferência com os bancos reais](../results/two-raters-pilot-audit-v2.json) confirmou as seis anotações humanas contra o corpus e os seis vetores de notas/auditorias contra os SQLite operacionais. Todas as chamadas foram reais, sem cache, com término `stop` e identidade de modelo/runtime/contrato/parâmetros consistente. Todas as referências usadas foram conferidas como `train`, fora dos hashes de validação e teste. A variante permanece opt-in e o teste adicional de 58 redações não foi usado.

## Executar a validação completa

Depois de compilar e gerar `target/classpath.txt`, os mesmos executores aceitam a seleção completa. Use diretórios e arquivos de saída novos em cada experimento para evitar reuso de bancos e cache:

```bash
java -cp "target/classes:$(cat target/classpath.txt)" \
  com.redator.corretor.cli.PairedBenchmarkCli \
  --benchmark eval/frozen/public-validation-two-raters-v2.json --arm baseline \
  --data-directory target/two-raters-full-baseline-new \
  --output target/two-raters-full-baseline-new.json

RAG_POLICY=calibration-v2 java -cp "target/classes:$(cat target/classpath.txt)" \
  com.redator.corretor.cli.PairedBenchmarkCli \
  --benchmark eval/frozen/public-validation-two-raters-v2.json --arm rag \
  --data-directory target/two-raters-full-rag-new \
  --output target/two-raters-full-rag-new.json

java -cp "target/classes:$(cat target/classpath.txt)" \
  com.redator.corretor.cli.RagQualityGateCli \
  eval/frozen/public-validation-two-raters-v2.json \
  target/two-raters-full-baseline-new.json target/two-raters-full-rag-new.json \
  target/two-raters-full-gate-new.json
```

O gate exige cobertura completa e resultados `READY`; uma falha ou revisão não pode ser removida da população para melhorar a métrica. O código de saída é diferente de zero em caso de reprovação.

## Verificação do código e pacote

Após as mudanças, passaram em sequência 94 testes unitários, 32 integrações offline (um live opt-in ignorado), três testes Node de configuração e 20 Playwright. O teste do executor também percorre Spring e SQLite reais com o novo formato humano; o provider fictício é mantido em revisão e não é evidência de qualidade do modelo. A [regressão de clique no editor](editor-click-regression.md) foi reproduzida com eventos e corrigida durante essa verificação.

O pacote de desenvolvimento dessa etapa foi gerado com sucesso e preservado em `target/corretor-enem-two-raters-v2.jar`, SHA-256 `be4a1b8b15c50ddc05e2008d752c083ff42c31cf241c0637149a0dea4db3e966`. As classes do novo gate e o HTML corrigido foram conferidos dentro do JAR. O SBOM separado continua CycloneDX 1.6, com 54 componentes de runtime, SQLite presente e JUnit ausente; SHA-256 `13aa4c4fa7974dce1922428e37df6ada0caa7995612bbc2410bc8abb47e2b431`.

O empacotamento com `release-quality` foi exercitado novamente e reprovou pelas regressões históricas de C2, C5 e QWK do `test-v1`. Isso confirma o bloqueio; não é uma aprovação de distribuição.
