# Catálogo público de exemplos humanos

O catálogo público é um banco SQLite separado do banco operacional que contém
as redações privadas dos alunos. O arquivo padrão é `dataset/public-corpus.db`
e fica ignorado pelo Git.

## Fontes e licenças

| Fonte | Uso neste projeto | Licença registrada |
| --- | --- | --- |
| KAMeL-USP `aes_enem_dataset` | Importação automatizada, benchmark e corpus de desenvolvimento | `Apache-2.0`, conforme o metadata do dataset |
| Extended Essay-BR | Importação automatizada, catálogo principal e corpus de desenvolvimento | `MIT`, conforme o repositório de origem |

Cada redação importada guarda fonte, subset, ID de origem, URL, licença SPDX,
hash normalizado, tema, split e uma ou mais avaliações humanas C1–C5. A revisão
Git exata do KAMeL é registrada em `corpus_import` para reprodução.

As avaliações individuais são preservadas. Na recuperação, cada redação aparece
uma única vez e expõe a média C1–C5 com a quantidade de avaliadores, evitando
gastar a janela de contexto com cópias do mesmo texto.

Linhas de origem sem texto são contabilizadas como `skippedRows` e não entram
no catálogo. Qualquer outra corrupção de formato interrompe a importação.
Cópias normalizadas já presentes em outra fonte são contabilizadas como
`duplicateRows`; o primeiro registro permanece canônico e seu split não muda.

## Isolamento contra vazamento

- `train`: elegível para busca/RAG experimental e para experimentos de treino;
- `validation` e `test`: elegíveis somente para benchmark;
- o mesmo texto normalizado não pode atravessar splits;
- uma busca aceita hashes excluídos, inclusive o da própria redação avaliada;
- nenhum texto privado de aluno é inserido automaticamente neste catálogo.

## Importar e inspecionar

```bash
mvn -q -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "target/classes:$(cat target/classpath.txt)" \
  com.redator.corretor.cli.PublicCorpusCli import-kamel \
  --config JBCS2025 --splits train,validation,test

java -cp "target/classes:$(cat target/classpath.txt)" \
  com.redator.corretor.cli.PublicCorpusCli import-extended

java -cp "target/classes:$(cat target/classpath.txt)" \
  com.redator.corretor.cli.PublicCorpusCli stats

java -cp "target/classes:$(cat target/classpath.txt)" \
  com.redator.corretor.cli.PublicCorpusCli search \
  --query "desinformação educação midiática" --limit 3
```

A busca lexical e determinística alimenta o prompt do Gemma somente com exemplos
de `train`. A política padrão é `lexical-v1`; `calibration-v2` é opt-in e preserva
início/conclusão das referências, informa o escopo das médias humanas e adapta
a quantidade de exemplos ao orçamento de contexto sem cortar a redação atual.

A qualificação exige comparação contra a mesma população humana congelada.
O [benchmark test-v1](../eval/reports/rag-public-test-v1.md) reprovou o RAG e permanece
preservado. O filtro legado por médias na escala oficial selecionava apenas três
das 66 redações de `validation`; esse [diagnóstico](../eval/reports/rag-validation-v2.md)
não substitui o teste de release.

O [protocolo com dois avaliadores](../eval/reports/two-raters-v2.md) preserva as
132 anotações individuais das 66 redações de validação. Cada nota individual segue
os seis níveis oficiais, inclusive quando a média entre os dois humanos não segue.
As métricas são calculadas por avaliador e depois promediadas; os rótulos não são
arredondados. A seleção de teste adicional tem 58 redações/116 anotações, excluindo
as 11 redações já presentes no `test-v1`. Ela permanece reservada.

O perfil Maven `release-quality` aceita ambos os protocolos e verifica os
artefatos e sua auditoria antes do empacotamento destinado à distribuição.
