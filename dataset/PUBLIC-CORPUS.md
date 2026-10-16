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

A busca ainda é lexical e determinística. Ela prepara a etapa 6, mas não altera
o prompt do Gemma nem habilita RAG em produção. A promoção exige comparação
cega entre baseline sem RAG e variante com RAG.
