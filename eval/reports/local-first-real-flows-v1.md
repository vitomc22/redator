# Fluxos locais com Spring e SQLite reais — 21/10/2026

Os fluxos críticos de documento, falha de transporte e correção humana têm cobertura de navegador sem interceptar APIs. Essa cobertura revelou duas falhas funcionais: notas humanas desapareciam do resultado e do progresso quando não havia avaliação de IA válida; o formulário local do professor perdia o cabeçalho JSON ao acrescentar sua identificação.

## Documento e fila local do professor

O [teste de PDF](../../e2e/document-upload-integration.spec.js) gera um PDF válido com conteúdo comprimido, que exige extração pelo parser. Confere o texto extraído, a recusa de avaliação antes da aprovação (HTTP 409), a exigência de confirmação no navegador, a correção da transcrição, o congelamento da versão e a recusa de edição posterior. Um segundo caso rejeita PDF inválido sem criar tentativa.

No mesmo fluxo, a avaliação fake continua em revisão e não publica total. O estudante solicita correção humana; o professor assume a solicitação, salva rascunho, recarrega e retoma os valores persistidos, publica e consulta o resultado e o progresso. O rascunho fica fora do progresso. A [evidência da execução](../results/pdf-local-teacher-e2e-v1.json) inclui respostas reais da API e conferência direta do SQLite: versões `DRAFT` e `PUBLISHED`, texto aprovado preservado e total humano 800. Essas notas são dados fictícios de teste de fluxo; não medem qualidade do modelo.

Antes da correção do cliente HTTP, o envio de rascunho usava `text/plain;charset=UTF-8` e retornava HTTP 500. A ordem dos spreads substituía o conjunto de cabeçalhos. O novo [cliente local](../../src/main/resources/static/api-client.js) combina os cabeçalhos depois das opções, preserva JSON e resolve `fetch` no momento do pedido, incluindo o wrapper de perfil local. Quatro testes Node verificam identificação, tipo explícito, propagação dos erros e o wrapper instalado após carregar o script.

## Correção humana independente da IA

O resultado apresenta as notas e orientações humanas publicadas mesmo sem execução de IA ou com execução pendente, falha ou em revisão. O progresso inclui a publicação humana sem inventar notas de IA e usa a publicação mais recente entre solicitações distintas. O filtro de proprietário continua aplicado.

Dois testes de integração cobrem ausência de IA e provider fake, exclusão de rascunhos, publicação, nova correção com versão reiniciada e isolamento entre perfis. O [fluxo cifrado do professor](../../e2e/teacher-review-exchange.spec.js) também verifica resultado, orientação e progresso após importar a resposta sem avaliar com IA. A [evidência desse fluxo](../results/human-only-progress-v1.json) preserva a execução anterior desta mesma correção, com total humano fictício 880.

## Falha real de transporte

`npm run e2e:failure` inicia o provider Ollama contra `127.0.0.1:1`, sem servidor nessa porta. Apenas esse cenário desativa a verificação/reparação automática do runtime no início; a configuração de produção não muda. O [teste](../../e2e/runtime-failure-integration.spec.js) verifica HTTP 503 `RUNTIME_UNAVAILABLE`, resultado sem notas, execução `FAILED` persistida e solicitação humana disponível após recarregar.

A [execução após a correção do cliente HTTP](../results/runtime-failure-e2e-v3.json) confirma zero registros de competências e zero chamadas de geração no SQLite. A conexão falha antes da geração. O campo interno `run.total=0` é um valor de armazenamento; a API publica `total=null`, sem apresentar esse zero como nota.

## Isolamento e validação

Cada execução Playwright inicia seu próprio Spring em `127.0.0.1:18080` e usa um diretório/SQLite novo com UUID. `reuseExistingServer=false` evita conectar a outro backend. Inspeção de um servidor manual exige `PLAYWRIGHT_EXTERNAL_SERVER=1` e usa 8080 por padrão. Falhas retêm trace desde a primeira tentativa. Seis testes Node verificam esses modos, a porta customizada e a incompatibilidade entre inferência live e falha deliberada.

Validação sequencial desta etapa:

- 99 testes unitários Java aprovados.
- 36 integrações Java aprovadas; um teste Gemma opt-in ignorado nesta rodada.
- Quatro testes do cliente HTTP e seis da configuração Playwright aprovados.
- 22 fluxos Playwright aprovados em 33,7 s.
- Um fluxo de falha real aprovado separadamente em 13,1 s.

Os testes de interface interceptados continuam úteis para estados visuais e não são contados como prova de integração. A [verificação Gemma anterior](stable-json-v1.md) mantém sua evidência real; os hashes de todos os fontes de inferência congelados foram reconferidos e não mudaram nesta etapa. Não foi feita nova geração. Os gates de qualidade das notas continuam reprovados; sucesso funcional não qualifica o RAG para release.

O pacote de desenvolvimento foi gerado após os testes: `target/corretor-enem-local-flows-v1.jar`, SHA-256 `93a3767a3b71ff746fe415b8423e5e488bc84e7b5a9ab0155ac8ad6b8a0bf383`. As classes, o HTML e o cliente HTTP foram comparados com os arquivos empacotados. O SBOM separado tem 54 componentes de runtime, sem dependências de teste. `mvn -q -Prelease-quality -DskipTests package` foi executado e terminou com código 1, pelas regressões reais de C2, C5 e QWK; o bloqueio permanece ativo.
