# Benchmark pareado Gemma/RAG — 20/10/2026

O gate de não regressão foi **reprovado**. Os dois braços concluíram as mesmas 11 redações públicas com status `READY`, mas o RAG aumentou o erro absoluto médio (MAE) em C2 e C5 e reduziu a concordância agregada (QWK). A qualificação de qualidade do RAG continua pendente.

## Artefatos e reprodução

- População humana congelada: [public-test-v1.json](../frozen/public-test-v1.json).
- Baseline Gemma sem RAG, preservado da execução anterior: [baseline-v1.json](../results/baseline-v1.json).
- Gemma com RAG, concluído nesta retomada: [rag-v1.json](../results/rag-v1.json).
- Resultado calculado pelo gate: [gate-v1.json](../results/gate-v1.json).
- Código executado: checkout `7f1663f`; esta retomada acrescenta resultados e documentação.

```bash
java -cp "target/classes:$(cat target/classpath.txt)" \
  com.redator.corretor.cli.RagQualityGateCli \
  eval/frozen/public-test-v1.json eval/results/baseline-v1.json \
  eval/results/rag-v1.json eval/results/gate-v1.json
```

O comando grava `passed: false` e encerra com código 1, conforme o contrato de reprovação. Não houve alteração dos limiares nem exclusão de amostras.

## Comparabilidade e auditoria

A exportação atual do corpus reproduziu integralmente o artefato congelado. A seleção contém 11 hashes distintos, consenso humano na escala 0/40/80/120/160/200 e os seguintes fingerprints:

- Seleção: `2957acd82d3e326efb1933b1aeaf8b708f382cab5216c65b83e889084347c13f`.
- Corpus: `480b13dd5e8e0f6aaf54b1f38e35d296d6ff888682eaaefaca39ae1bb055cd57`.

Os braços usaram Spring e SQLite reais em bancos operacionais separados. A execução anterior interrompida com RAG foi preservada; esta retomada usou `target/benchmark-rag-v1-continuation`.

As auditorias por hash confirmam Ollama `0.40.4`, Gemma `gemma4:e2b-it-q4_K_M`, digest `b37049369adfe3d2b653af0ab301a062ca5cbe96ace6aa0d3f2559ee9b563fc2`, mesma rubrica/schema versionados e parâmetros idênticos: contexto 8192, saída máxima 1200, quatro threads, temperatura 0, seed 42, thinking desativado e keep-alive de cinco minutos.

Cada uma das 11 avaliações com RAG registrou uma chamada real, sem cache reutilizado, término `stop` e três referências auditáveis. As 33 referências, correspondentes a 12 exemplos distintos, foram conferidas no corpus: todas pertencem a `train`, seus hashes correspondem aos IDs e nenhuma é a própria redação avaliada. O fingerprint do corpus nas chamadas coincide com o congelado.

## Métricas

O gate exige MAE sem aumento em cada competência e QWK agregado calculável sem redução. As métricas abaixo usam as 11 redações completas; MAE está em pontos de competência, na escala 0–200.

| Competência | MAE sem RAG | MAE com RAG | Não regressão |
| --- | ---: | ---: | --- |
| C1 | 25,4545 | 25,4545 | Aprovada |
| C2 | 76,3636 | 80,0000 | Reprovada |
| C3 | 80,0000 | 80,0000 | Aprovada |
| C4 | 36,3636 | 36,3636 | Aprovada |
| C5 | 80,0000 | 94,5455 | Reprovada |

O QWK agregado caiu de **0,126394** para **0,031611**, também reprovando o gate.

As latências registradas somaram 2027,990 s no baseline e 3088,671 s com RAG; as médias foram 184,363 s e 280,788 s por redação, respectivamente. São observações destas execuções locais, sem controle adicional da carga da máquina.

## Validação e próximos critérios

Na retomada foram executados, em sequência, 67 testes unitários aprovados, 32 testes de integração (31 aprovados e um live opt-in ignorado) e a suíte Playwright. A confirmação completa aprovou 20 testes Playwright. Uma falha inicial no avanço editor → inspeção não reapareceu na execução isolada, nas dez repetições do arquivo afetado (20 testes) nem na confirmação completa. Ela foi reproduzida posteriormente com captura de eventos, identificada como rolagem suave de foco durante o clique e [corrigida com teste de regressão](editor-click-regression.md). Os fluxos de interface interceptados não são usados como evidência de integração com Gemma; a evidência real de inferência está nos dois braços auditados.

Esta população pequena mede o gate congelado e não demonstra qualidade pedagógica geral. `READY` comprova o contrato de publicação, sem implicar concordância com o consenso humano.

A etapa posterior de [calibração em validation](rag-validation-v2.md) preserva esta reprovação e usa uma configuração revisada identificada por hash. O gate foi integrado ao empacotamento pelo perfil Maven `release-quality`, que bloqueou os artefatos medidos deste relatório. A qualificação de qualidade do RAG continua pendente e ele permanece experimental.
