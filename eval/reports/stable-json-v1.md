# Ordenação estável de mapas JSON — 21/10/2026

O modo experimental `APP_LLM_STABLE_JSON=true` estabiliza a ordem das chaves dos mapas enviados ao modelo e usados no fingerprint de cache. Ele permite preparar novas comparações com a mesma serialização entre processos Java. O contrato padrão e as execuções históricas permanecem preservados.

## Comportamento

O contrato legado serializa mapas na ordem fornecida. Por exemplo, mapas com as mesmas chaves e valores, inseridos em ordens diferentes, geram strings e hashes diferentes. Os pedidos também usam `Map.of`: a [documentação do Java 21](https://docs.oracle.com/en/java/javase/21/core/creating-immutable-lists-sets-and-maps.html) descreve a ordem de iteração aleatória e sua variação entre execuções da JVM.

O novo contrato ordena as chaves de mapas recursivamente, preservando a ordem das listas, caracteres Unicode e trechos literais. A versão é `stable-map-json-v1`; ela não pretende implementar uma especificação geral de canonicalização de números ou de todos os objetos JSON.

O `promptHash` inclui a versão de serialização, resultando em `880f23b5a71ab2620ea2f9d801db9ceae51b6cf9c5cb86ac82abc38cd6e9a1d1`. Isso separa auditoria e cache do contrato legado mesmo quando um pedido individual teria a mesma ordem de chaves por coincidência. Rubrica, schema e candidatos de evidência conservam o conteúdo original.

O modo é selecionado por propriedade Spring e injetado no mesmo pipeline existente. O provider fake continua produzindo revisão. Os artefatos com o novo hash são rejeitados pelo gate de release do contrato legado; qualidade exigirá evidência própria.

## Verificação

- Teste do contrato: mapas aninhados com ordens distintas produzem o mesmo JSON; listas e Unicode são preservados.
- Quatro processos Java independentes produziram o mesmo hash de mensagens.
- A configuração Spring mantém o contrato legado por padrão e seleciona o estável quando ativado.
- Integração com SQLite: as avaliações persistem o hash selecionado, e os caches legado/estável não se confundem; cada um volta a fornecer seu próprio resultado em leituras seguintes.
- Validação sequencial: 99 unitários aprovados, 34 integrações offline aprovadas e um live opt-in ignorado, três testes Node de configuração aprovados e 20 Playwright aprovados em 41,7 s com o modo estável ativado.
- O SQLite do navegador confirmou o novo hash nas chamadas persistidas.

A [configuração da verificação real](../config/stable-json-live-v1.json) foi congelada antes da geração: modo estável, RAG `calibration-v2`, Gemma e parâmetros fixados, coleção SQLite nova. Essa verificação é funcional e não substitui os benchmarks de notas humanas.

A [execução real e as reconstruções do pedido](../results/stable-json-live-v1.json) passaram: uma chamada sem cache, término `stop`, resultado `READY`, cinco notas oficiais persistidas e latência de 276,533 s. O total publicado foi 840; esta execução não tem rótulo humano de referência e não mede qualidade de correção.

Dois processos Java novos reconstruíram o pedido a partir do SQLite, consultando somente metadados do modelo. Ambos reproduziram exatamente o `requestMessagesHash` e a chave completa de cache da chamada real, sem executar `/api/chat`. Os parâmetros, digest/runtime e hashes dos fontes foram conferidos com a configuração congelada.

Somando a rodada offline e o opt-in real separado, os 134 testes Java distintos passaram. O pacote desta etapa, preservado em `target/corretor-enem-stable-json-v1.jar`, foi gerado e conferido contra as classes e o HTML compilados: JAR SHA-256 `8344fa5e958eb1f8279ff2caac9e32c1ee7f3eae2d4d5059cfe480a298895a2c`. O SBOM separado conserva 54 componentes de runtime e SHA-256 `13aa4c4fa7974dce1922428e37df6ada0caa7995612bbc2410bc8abb47e2b431`.

## Uso em novos experimentos

```bash
APP_LLM_STABLE_JSON=true RAG_POLICY=calibration-v2 \
  java -cp "target/classes:$(cat target/classpath.txt)" \
  com.redator.corretor.cli.PairedBenchmarkCli \
  --benchmark eval/frozen/public-validation-two-raters-v2.json --arm rag \
  --data-directory target/stable-json-validation-rag-new \
  --output target/stable-json-validation-rag-new.json
```

Ative `APP_LLM_STABLE_JSON=true` também no baseline, com diretório e saída próprios. Registre a configuração antes da inferência. As [execuções anteriores do protocolo com dois humanos](two-raters-v2.md) mantêm suas notas, hashes e reprovações originais.
