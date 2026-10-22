# Progresso da implementação local-first

Este arquivo acompanha a execução de `plano_redator_local_first.md`. Um item somente muda para concluído quando seus testes automatizados e critérios de saída estiverem verdes.

## Fase 0 — integridade e baseline

- [x] Inventário e revisão paralela do código.
- [x] Teste de integração para acesso cruzado entre dois perfis.
- [x] Propriedade verificada em leitura, edição, avaliação e consulta de execução.
- [x] Rotas legadas registram o proprietário local.
- [x] Servidor limitado a loopback.
- [x] Rascunhos do frontend separados por perfil.
- [x] Primeiro E2E sem interceptação, usando navegador + Spring + SQLite reais.
- [x] Publicação da avaliação de IA inteiramente transacional.
- [x] Testes de rollback para publicação humana e da IA.
- [x] Token efêmero por processo entre UI e API local, em cookie HttpOnly/SameSite Strict.
- [x] Cobertura dos fluxos críticos com navegador + Spring + SQLite reais: documento/conferência, falha de transporte, fila humana com rascunho/retomada/publicação, importação cifrada, resultado/progresso sem IA, histórico, checklist e reescrita. Os cenários interceptados restantes verificam a interface. [Evidências](../eval/reports/local-first-real-flows-v1.md).

## Fase 1 — núcleo local-first

- [x] Diretório de dados configurável e layout local seguro, pronto para o launcher escolher o caminho por sistema operacional.
- [x] Banco privado por perfil e migração do banco legado, com atualização de schema somente na cópia, preservação da origem e snapshot bruto, ativação idempotente e backup/restauração restritos ao proprietário. [Validação](../eval/reports/profile-databases-v1.md).
- [x] Preparação offline da migração: snapshot consistente, coleções privadas por proprietário, manifesto/hashes, preservação integral dos dados sem dono e recusa de schema/relações desconhecidos. Dez integrações e execução CLI sobre seis perfis do SQLite real verificadas. [Ferramenta e evidências](../eval/reports/profile-collections-v1.md).
- [x] Backup consistente, importação pela UI e restauração aplicada antes do datasource, com snapshot de segurança.
- [x] Launcher local com preflight, diretório privado, porta loopback livre, modos foreground/background, PID validado, status, logs e encerramento seguro.
- [x] Exportação/importação integral da coleção SQLite pela interface, com validação de integridade e schema.

## Fase 2 — instalação Gemma

- [x] Manifesto versionado de Ollama/Gemma com imagem, digest e quantização fixados.
- [x] Descoberta e instalação nativa automática do Ollama por instalador oficial versionado e verificado por SHA-256, com Docker como fallback avançado.
- [x] Download automático do Gemma padrão pelo protocolo Ollama, com retomada de camadas e recusa de digest divergente.
- [x] Smoke test de geração estruturada e integração Spring/SQLite/RAG/Gemma real opt-in.
- [ ] Instaladores limpos para Linux, Windows e macOS.

## Fase 3 — avaliação e RAG

- [x] Contrato único e versionado de prompt, rubrica e schema, com hashes incluídos no fingerprint/auditoria.
- [x] Cache SQLite limitado, persistente, com TTL e LRU por fingerprint completo do contrato/configuração.
- [x] Fingerprint lógico determinístico consistente com SQLite WAL/checkpoints.
- [x] Deduplicação exata e aproximada por SimHash, com bloqueio transacional de vazamento entre splits.
- [x] Benchmark humano congelado em 11 amostras públicas estritamente compatíveis com os seis níveis oficiais, exportação reproduzível, executor pareado auditável e gate por hash/configuração.
- [x] Dois braços Gemma sem RAG × com RAG concluídos e registrados no repositório, cada um com 11 resultados `READY`; gate executado sobre a população completa. [Relatório e artefatos](../eval/reports/rag-public-test-v1.md).
- [x] Gate integrado ao empacotamento pelo perfil Maven `release-quality`, com cobertura completa, escala inteira, corpus congelado, contrato atual, digest/runtime/parâmetros e referências auditadas. Empacotamento foi bloqueado pela reprovação real preservada.
- [x] Política de calibração opt-in com início/conclusão dos exemplos, escopo das médias humanas explícito, hashes das mensagens/excertos e redução auditada de referências para reservar contexto sem truncar a redação do estudante.
- [x] Comparação em `validation` entre baseline, RAG antigo e variante concluída, com 3/3 `READY` em cada braço. Configuração/código da variante identificados por hash antes da inferência. Variante reprovou C4 e QWK agregado; continua opt-in. [Relatório](../eval/reports/rag-validation-v2.md).
- [x] Protocolo que preserva os dois avaliadores humanos e tira a média das métricas individuais, sem arredondar notas nem excluir discordâncias válidas. Seleções congeladas: 66 redações/132 anotações em validação e outras 58 redações/116 anotações de teste, excluindo as onze de `test-v1`. [Método e artefatos](../eval/reports/two-raters-v2.md).
- [x] Preparação sem inferência das 66 redações nos dois modos: orçamento de contexto respeitado em todas, com texto integral e omissões opcionais auditadas.
- [x] Piloto do protocolo com dois humanos concluído: três redações novas de validação, seis chamadas Gemma reais sem cache e 3/3 `READY` por braço. Vetores de notas e auditorias conferidos com os SQLite; gate reprovou C3, C5 e QWK médio. [Resultados](../eval/reports/two-raters-v2.md).
- [x] Modo experimental de ordenação estável de mapas JSON, com hash de contrato e cache separados; testes em quatro processos Java, SQLite real e 20 fluxos Playwright aprovados. Uma chamada Gemma real passou, e dois processos novos reconstruíram exatamente os hashes das mensagens e do cache sem nova inferência. [Verificação](../eval/reports/stable-json-v1.md).
- [x] Piloto opt-in de evidências distribuídas pela redação inteira, com três novas redações de validação e duas notas humanas individuais. Ambos os braços concluíram 3/3 `READY`; notas e referências conferidas nos SQLite e requisições reproduzidas por hash. O QWK médio melhorou de -0,147091 para 0,088274, mas C5 regrediu de MAE 80 para 93,3333; a variante não foi promovida. [Medição](../eval/reports/grounded-v3.md).
- [ ] Executar a comparação completa das 66 redações no novo protocolo e revisar a hipótese de qualidade em validação; o piloto de integração não substitui essa execução.
- [ ] Aprovar o gate de não regressão para qualificar o RAG. A execução de `test` de 20/10/2026 reprovou C2 e C5 por aumento de MAE e o QWK agregado por queda de 0,126394 para 0,031611. Qualidade continua pendente; `validation` não substitui o teste de release.

## Fase 4 — pacote do professor

- [x] Envelope criptográfico versionado compartilhado por pedido e resposta.
- [x] Cifragem AES-256-GCM, integridade, expiração, revogação e proteção persistente contra replay.
- [x] Importação idempotente e transacional.
- [x] Modo Professor stateless, sem JDBC, datasource ou contexto do perfil do estudante.
- [x] E2E de ida e volta por arquivo, incluindo pacote incompleto e revogação.

## Fase 5 — portal/relay opcional

- [ ] SPA estática do professor.
- [x] Núcleo do relay de blobs opacos com TTL; a integração WebCrypto da SPA ainda está pendente.
- [x] Capacidades separadas, armazenadas apenas como hash, com revogação e consumo único.
- [ ] E2E online e fallback por arquivo.

## Fase 6 — treino estilo Anki

- [x] Cartões derivados das competências publicadas pela IA ou pelo professor.
- [x] Agenda local de repetição espaçada.
- [x] Sessões, metas e progresso diário explicável e isolado por perfil.
- [x] Backup/restauração dos cartões, metas e sessões junto da coleção SQLite integral.

## Fase 7 — distribuição

- [x] Build JAR reproduzível e SBOM CycloneDX 1.6 automático com dependências de runtime.
- [ ] Assinatura/notarização.
- [ ] Atualização assinada e rollback.
- [ ] Matriz pública de hardware e licenças.

## Último baseline

- Continuação final (21/10/2026): 108 unitários, 54 integrações offline, 11 testes Node e 25 cenários de navegador aprovados. Inclui banco padrão por perfil, migração V5 somente na cópia, restauração após reinício, falha real de conexão e geração Gemma pelo JAR em Java 21.0.12.1. O fluxo real corrigido omitiu uma referência para caber no contexto, persistiu uma chamada sem cache e publicou 1000 contra referência humana 800; qualidade segue experimental. [Validação](../eval/reports/profile-databases-v1.md).
- Pacote final desta continuação: JAR SHA-256 `b801749720b98178c7e45fcd52b5f8ae5aae9e80f328bfd8d4babaf706ac6693`, SBOM CycloneDX 1.6 com 54 componentes de runtime, classes e recursos conferidos no artefato. A geração do SBOM agora antecede o empacotamento, e o CI inspeciona o JAR. O gate de release continua reprovado; este é um build de desenvolvimento para uso local experimental.
- Baseline Java anterior (21/10/2026): 99 testes unitários e 46 integrações offline aprovados; um teste Gemma opt-in ignorado nessa rodada. A integração Gemma real foi aprovada separadamente antes das alterações de resultado/progresso humano e da ferramenta offline de coleções. Total então registrado: 146 testes Java distintos aprovados ao longo das rodadas.
- Node: seis testes de configuração Playwright e quatro do cliente HTTP aprovados. Toda execução gerencia seu próprio backend em 18080 e SQLite novo; inspeção de servidor externo exige opção explícita.
- Java live: `OllamaLiveIntegrationTest` aprovado em 233,8 s com SQLite novo, política padrão `lexical-v1`, Ollama 0.40.4 e Gemma `gemma4:e2b-it-q4_K_M` no digest fixado; uma chamada sem cache. [Auditoria](../eval/results/default-live-v1.json).
- Java live com JSON estável (21/10/2026): aprovado com `calibration-v2`, uma geração real sem cache em 276,533 s e SQLite novo. Dois processos independentes reproduziram a chave de cache e o hash das mensagens originais sem gerar novas respostas. [Auditoria](../eval/results/stable-json-live-v1.json).
- Playwright (21/10/2026): 22 aprovados em 33,7 s, mais um cenário separado de falha real de transporte aprovado em 13,1 s. A fila humana agora salva/retoma rascunho e publica pelo navegador; resultado e progresso exibem notas humanas sem depender da IA. [Relatório](../eval/reports/local-first-real-flows-v1.md).
- Regressão do editor (20/10/2026): 20 aprovados em 56 s após corrigir o clique perdido no avanço editor → inspeção. A captura mostrou rolagem suave de foco movendo o botão entre a pressão e a soltura; três envios falharam antes e os mesmos três passaram depois. [Reprodução e correção](../eval/reports/editor-click-regression.md). A suíte inclui isolamento local, histórico/reescrita/checklist persistentes, pacote cifrado do professor, treino espaçado e exportação/restauração contra backend e SQLite reais.
- Playwright com modo JSON estável (21/10/2026): 20 aprovados em 41,7 s; o SQLite do backend confirmou a identidade do contrato selecionado.
- Benchmark Gemma real (20/10/2026): baseline e RAG com 11/11 `READY`, uma chamada por amostra e 33 referências RAG verificadas no split `train`. Gate reprovado em C2, C5 e QWK agregado; resultado preservado em `eval/results/gate-v1.json`.
- Playwright live com `calibration-v2`: fluxo funcional aprovado em 5,9 minutos com backend, SQLite e Gemma reais. A nota 1000 divergiu da referência humana 800; o erro de 200 pontos foi preservado e a tolerância histórica de 80 continua disponível em `npm run e2e:quality`.
- Playwright de qualidade: executado separadamente, reprovou novamente com 1000 contra 800 em uma coleção SQLite nova. A variante permanece experimental e o release bloqueado.
- Build de desenvolvimento: JAR e SBOM gerados por `mvn -q -DskipTests package` após as verificações de 21/10. JAR SHA-256 `99ea34b72bd9f6787a05e6a84486fa9f5c542474b12e6fc868999f581ec76977`; classes, HTML, cliente HTTP e CLI de coleções conferidos dentro do pacote. O perfil `release-quality` continua bloqueando distribuição pelas regressões reais de C2, C5 e QWK do teste legado. Os JARs anteriores foram preservados em `target/corretor-enem-two-raters-v2.jar` , `target/corretor-enem-stable-json-v1.jar` e `target/corretor-enem-local-flows-v1.jar`.
- SBOM conferido como CycloneDX 1.6 com 54 componentes de runtime, presente em `META-INF/sbom/application.cdx.json` no JAR; cópia separada ao lado do JAR em `target/corretor-enem-0.1.0-SNAPSHOT.sbom.json`.
- `git diff --check`: sem erros.
