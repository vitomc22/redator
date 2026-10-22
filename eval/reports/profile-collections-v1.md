# Preparação de coleções privadas por perfil — 21/10/2026

A ferramenta `ProfileCollectionCli` prepara a separação do banco legado sem iniciar Spring, Ollama ou acesso à rede. Abre a origem somente para leitura, cria um snapshot consistente com `VACUUM INTO` e prepara uma coleção SQLite para cada proprietário encontrado. O aplicativo continua usando seu banco atual; o resultado declara `PREPARED_NOT_ACTIVATED`.

## Uso

Escolha um diretório de saída novo:

```bash
mvn -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "target/classes:$(cat target/classpath.txt)" \
  com.redator.corretor.cli.ProfileCollectionCli \
  --source /caminho/Redator/redator.db \
  --output /caminho/colecoes-preparadas
```

O diretório resultante contém `legacy-snapshot.sqlite`, `manifest.json` e `profiles/<id>/redator.sqlite`. O manifesto registra SHA-256 do snapshot e de cada coleção, contagens e dados sem proprietário. Um destino existente é recusado. Em sistemas POSIX, a raiz de saída tem acesso apenas pelo proprietário.

A continuação adicionou `legacy-source.sqlite`: um snapshot bruto, anterior a qualquer atualização de schema. O Flyway atualiza somente a cópia `legacy-snapshot.sqlite` para V10 antes da separação. Isso permite importar bancos antigos, como V5, preservando a origem e seu formato original. As duas cópias recebem hashes no manifesto, junto da impressão lógica da origem.

## Preservação e isolamento

Cada coleção mantém IDs e vínculos de redações, tentativas, execuções, competências, auditorias LLM, notas humanas legadas, pedidos, versões humanas, checklist, pacotes de troca, cartões, revisões, metas e sessões. Perfis que possuem somente metas ou sessões também são incluídos. Os temas públicos permanecem disponíveis.

O cache legado não permite descobrir o proprietário de cada resposta, e os canais de relay também não têm esse vínculo. Eles ficam no snapshot completo e não entram nas coleções individuais. Chamadas LLM sem execução vinculada recebem o mesmo tratamento. O comando não atribui dados sem dono a um estudante por suposição.

A limpeza das cópias usa transação, verificação de chaves estrangeiras, `secure_delete` e `VACUUM`. O teste inclui marcadores de texto privado e verifica sua ausência nos bytes das outras coleções, além das consultas SQL. O snapshot completo continua privado e conserva os dados originais, inclusive aqueles sem proprietário.

## Limites de schema e publicação

Após atualizar a cópia com as migrações versionadas, o resultado precisa ter o DDL exato V10, fingerprint `43225ab911bbaeeae8cbf6cfc920b0d08289e256dec9504252184e5992b3d3df`. Recusa tabelas, colunas, views ou outros objetos inesperados, em vez de transportar dados cujo significado não conhece. Identificadores inválidos, referências quebradas e relações entre proprietários também impedem a preparação.

O trabalho ocorre em um diretório temporário privado. Apenas a árvore completa é renomeada para o destino; erros removem os arquivos temporários. A ferramenta não instala o resultado nem substitui bancos existentes.

## Evidência

Os dez testes de integração cobrem snapshot com WAL ativo, preservação do grafo completo, ausência física de textos de outros perfis, perfis sem redação, banco vazio, colisão de destino, origem ausente, relações inconsistentes, schema desconhecido e identificador inválido. A origem permanece com os mesmos bytes nos casos de sucesso e recusa.

A [execução em processo Java separado](../results/profile-export-v1.json) usou o SQLite dos fluxos reais Playwright: seis perfis, sete redações, três execuções, quatro versões humanas, cinco cartões e duas sessões. As seis saídas tiveram hashes, contagens por proprietário, `integrity_check` e `foreign_key_check` conferidos. Os registros completos das treze tabelas privadas foram comparados entre origem, snapshot e união das coleções; todos coincidiram. Nenhum dado de usuário real foi necessário para essa verificação.

A seleção automática do datasource por perfil e o backup/restauração foram implementados na etapa seguinte, descrita em [bancos por perfil](profile-databases-v1.md). Esta ferramenta continua sendo apenas o preparador offline; a ativação é responsabilidade do aplicativo.

A rodada final passou 99 testes unitários e 46 integrações Java; um teste Gemma opt-in foi ignorado. Não há novo fluxo de navegador nesta ferramenta offline; os 22 fluxos e a falha real de transporte passaram na etapa imediatamente anterior. O JAR de desenvolvimento atualizado inclui a CLI e o serviço, comparados com as classes compiladas: SHA-256 `99ea34b72bd9f6787a05e6a84486fa9f5c542474b12e6fc868999f581ec76977`. O SBOM de 54 componentes está em `META-INF/sbom/application.cdx.json` e em arquivo separado.
