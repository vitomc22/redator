# Plano do Redator ENEM local-first

**Versão:** 1.0  
**Data:** 19/10/2026  
**Estado:** plano canônico para as próximas implementações  
**Substitui para decisões futuras:** `plano_corretor_enem_ia_mvp_v2.md`

## 1. Visão do produto

O Redator será um aplicativo gratuito, instalável e local-first para treino de redação do ENEM. A experiência deve se aproximar do Anki: a pessoa instala, mantém sua coleção e histórico no próprio computador, trabalha sem conta e continua usando as funções essenciais sem internet.

O produto não será um SaaS. O computador do estudante é a autoridade sobre redações, versões, correções, métricas e preferências. Serviços externos nunca serão necessários para abrir o histórico ou continuar estudando.

Gemma, executado localmente pelo Ollama, será o corretor de IA padrão e um pré-requisito gerenciado pela instalação. Provedores adicionais podem existir como plugins, mas não podem substituir o caminho local padrão nem enviar texto para a nuvem sem consentimento explícito.

Uma pessoa poderá pedir ajuda a um professor por meio de um pacote de correção limitado a uma redação e uma revisão. O professor poderá corrigir pelo navegador através de um link de capacidade. O serviço de troca, quando usado, guardará somente conteúdo cifrado e temporário. A correção recebida será validada e incorporada ao SQLite local.

## 2. Princípios não negociáveis

1. **Local-first:** leitura, escrita, histórico, IA e RAG funcionam localmente.
2. **Sem conta obrigatória:** perfis são locais e não representam autenticação remota.
3. **SQLite como fonte de verdade:** um banco privado por perfil/coleção e um corpus público separado.
4. **Privacidade por padrão:** servidor HTTP apenas em loopback; nenhuma redação sai da máquina sem ação explícita.
5. **Gemma padrão:** instalado/verificado automaticamente e testado no primeiro uso.
6. **Reprodutibilidade:** modelo, digest, prompts, rubrica, corpus e parâmetros registrados em cada execução.
7. **Notas honestas:** falha, incerteza ou revisão pendente nunca viram nota zero ou nota fabricada.
8. **Humano e IA separados:** avaliações humanas não são sobrescritas nem apresentadas como avaliações da IA.
9. **TDD e pirâmide de testes:** unidade, integração real e E2E de usuário para todo fluxo crítico.
10. **Distribuição simples:** instalador por sistema operacional; Docker apenas para desenvolvimento e instalação avançada.

## 3. Arquitetura alvo

```text
┌──────────────────────── computador do estudante ────────────────────────┐
│ Aplicativo desktop/launcher                                              │
│   ├─ abre UI em http://127.0.0.1:<porta-aleatoria>                       │
│   ├─ inicia backend Java empacotado                                      │
│   ├─ verifica Ollama + Gemma                                             │
│   └─ backup, diagnóstico, importação e atualização                       │
│                                                                          │
│ UI web local ── API local ── serviços de domínio                         │
│                              ├─ SQLite privado (coleção do estudante)    │
│                              ├─ SQLite público (corpus RAG, somente leitura)
│                              ├─ Ollama/Gemma local                       │
│                              └─ caixa de entrada/saída de pacotes        │
└──────────────────────────────────────────────────────────────────────────┘
                         │ ação explícita: compartilhar
                         ▼
┌──────────── relay opcional, sem conhecimento do conteúdo ────────────────┐
│ página estática do professor + blobs cifrados + expiração + uso único    │
│ sem histórico acadêmico, sem nota em claro, sem banco de alunos          │
└──────────────────────────────────────────────────────────────────────────┘
```

### 3.1 Processos locais

- `redator-launcher`: detecta porta livre, prepara diretórios e inicia os componentes.
- `redator-core`: Spring Boot ligado exclusivamente a `127.0.0.1`.
- `ollama`: runtime local. Pode ser instalação nativa gerenciada ou container em modo avançado.
- `gemma`: modelo padrão fixado por manifesto e digest. O primeiro início baixa o modelo automaticamente; o instalador offline opcional traz as camadas autorizadas pela licença.
- navegador: aberto pelo launcher; não é necessário Electron na primeira versão.

### 3.2 Diretórios por plataforma

O código deve usar APIs de diretórios de aplicação, nunca caminhos relativos ao diretório atual.

```text
<dados-do-usuário>/Redator/
├── profiles/<profile-id>/redator.sqlite
├── corpus/public-corpus.sqlite
├── backups/
├── exchange/inbox/
├── exchange/outbox/
├── logs/
└── runtime/manifest.json
```

Segredos de compartilhamento e chaves privadas devem usar o cofre do sistema operacional quando disponível. Não devem ficar no SQLite em texto puro.

### 3.3 Bancos separados

**Banco privado:** perfis, temas pessoais, redações, revisões, execuções de IA, avaliações humanas, agenda de treino e pacotes importados.  
**Banco público:** exemplos licenciados, proveniência, avaliações humanas agregadas, embeddings/índice e divisão `train/validation/test`.

O banco público não referencia IDs do banco privado. Uma redação particular nunca vira exemplo RAG automaticamente. Exportação para pesquisa exigirá fluxo independente de consentimento e anonimização, fora do MVP local-first.

## 4. Modelo de domínio

Entidades novas ou consolidadas:

- `local_profile`: identidade somente local, nome exibido e preferências.
- `essay`: texto lógico e tema.
- `essay_revision`: versão imutável, hash e origem (`typed`, `ocr`, `imported`).
- `evaluation_run`: execução contra uma revisão, com estado explícito.
- `competence_assessment`: C1–C5, justificativas e evidências.
- `human_review_request`: pedido local e estado da troca.
- `human_review`: correção humana imutável vinculada à revisão exata.
- `review_package`: manifesto, hash da revisão, expiração, nonce e estado de importação.
- `training_card`: agenda de retomada inspirada em repetição espaçada.
- `runtime_component`: versão/digest de Java, Ollama, Gemma, rubrica e corpus.

Estados de avaliação:

```text
DRAFT -> QUEUED -> RUNNING -> COMPLETED
                         ├-> NEEDS_REVIEW
                         └-> FAILED
```

`COMPLETED` publica competências e total. `NEEDS_REVIEW` pode guardar competências provisórias, mas total permanece nulo. `FAILED` não publica competências.

## 5. Correção remota por professor

### 5.1 Pacote de ida

O estudante escolhe uma revisão e confirma exatamente o que será enviado:

- texto da revisão;
- tema e textos motivadores autorizados;
- pseudônimo opcional;
- campos C1–C5 esperados;
- prazo e mensagem ao professor;
- opcionalmente a avaliação da IA, desmarcada por padrão para evitar ancoragem.

O aplicativo cria um manifesto versionado, associa o hash da revisão, cifra o conteúdo no dispositivo e produz:

- arquivo `.redator-review` para envio por qualquer canal; e/ou
- link de capacidade cujo fragmento contém o segredo que não é enviado ao relay.

### 5.2 Portal do professor

O portal será uma aplicação web estática. Ao abrir o link, ela baixa o blob, decifra no navegador e apresenta somente o pacote autorizado. O professor preenche notas válidas em passos de 40, comentários por competência, comentários gerais e sugestão de reescrita.

Não haverá acesso ao restante do histórico, busca de alunos ou painel central. O professor devolve um blob cifrado ou baixa o arquivo de resposta.

### 5.3 Importação

O aplicativo do estudante deve validar:

- versão do protocolo e esquema;
- identificador e nonce do pedido;
- hash da revisão original;
- integridade criptográfica;
- expiração e replay;
- faixa e passo das notas;
- ausência de campos inesperados executáveis.

A importação é transacional e idempotente. Um pacote nunca altera a redação; cria uma `human_review`. Conflitos ficam lado a lado e exigem escolha explícita do estudante.

### 5.4 Relay opcional

O relay não faz parte da fonte de verdade. Requisitos:

- blobs opacos, tamanho limitado, expiração curta e exclusão após retirada/expiração;
- IDs aleatórios não enumeráveis, rate limit e nenhuma indexação pública;
- chave de decifragem somente no fragmento do link;
- logs sem texto, nota, tema ou segredo;
- possibilidade de auto-hospedagem;
- fluxo por arquivo sempre disponível se o relay estiver fora do ar.

## 6. Gemma e instalação

### 6.1 Experiência de instalação

Distribuir instaladores para Linux, Windows e macOS. Cada artefato inclui o runtime Java e o launcher. No primeiro início:

1. verifica espaço, RAM, CPU e suporte do sistema;
2. instala ou localiza Ollama compatível;
3. inicia o runtime somente em loopback;
4. baixa o Gemma fixado no manifesto, com progresso, retomada e checksum;
5. executa teste curto de geração e valida JSON estruturado;
6. instala/verifica o corpus público;
7. cria o banco privado e um backup inicial;
8. abre o tutorial.

Se a máquina não comportar o modelo padrão, o instalador deve explicar o requisito e oferecer um Gemma menor aprovado, mantendo a família Gemma como padrão. Nunca deve mudar silenciosamente para uma API de nuvem.

### 6.2 Licenças

O instalador deve apresentar e registrar os termos aplicáveis ao Gemma e ao Ollama. A licença do projeto não relicencia pesos do modelo ou datasets. Antes de publicar um instalador offline contendo pesos, confirmar formalmente o direito de redistribuição. Até essa confirmação, “incluído na instalação” significa download automático, verificado e sem configuração manual.

### 6.3 Docker

`compose.ollama.yml` continua útil para desenvolvimento, CI especializado e usuários avançados. Docker não será requisito do instalador comum. O Maven não deve iniciar containers implicitamente em todo build; preparação de runtime pertence ao launcher e a perfis explícitos.

## 7. RAG funcional

O RAG fará parte do caminho de produção local, não será rotulado como experimental quando cumprir os gates abaixo.

Pipeline:

1. importar corpora públicos com licença e proveniência por registro;
2. normalizar sem alterar o texto original preservado;
3. remover duplicatas exatas e quase duplicatas antes dos splits;
4. separar por grupo de tema/fonte para evitar vazamento entre treino e avaliação;
5. produzir índice lexical local inicialmente e embeddings locais quando aprovados;
6. recuperar exemplos por tema, faixa e competência, somente do split `train`;
7. limitar exemplos e tokens; registrar IDs, hashes e política;
8. injetar exemplos como referência não normativa, subordinados à cartilha;
9. calibrar contra `validation`; publicar métricas apenas sobre `test` congelado.

Gates para declarar funcional:

- nenhuma redação privada indexada;
- nenhuma amostra de `validation/test` recuperável;
- fingerprint consistente com SQLite em WAL;
- avaliação comparativa comprova melhora ou não regressão nas cinco competências;
- testes contra prompt injection nos exemplos;
- comportamento previsível com corpus ausente/corrompido;
- auditoria mostra corpus, exemplos e versão da política usados.

## 8. Segurança e confiabilidade local

- vincular servidor a loopback e usar porta aleatória ou reservada com verificação;
- token efêmero entre UI e API local para reduzir requisições de páginas externas;
- política CORS negada por padrão e cabeçalhos seguros;
- toda consulta por ID inclui `profile_id` no repositório/SQL, não apenas no controller;
- operações de publicação/importação anotadas com transação;
- WAL, `foreign_keys=ON`, `busy_timeout` e migrações com teste de upgrade;
- backup consistente pela API de backup do SQLite ou checkpoint controlado;
- cópia automática antes de migração e restauração testada;
- logs estruturados sem texto integral da redação;
- importadores resistentes a zip bomb, path traversal, MIME falso e arquivos grandes;
- nenhum HTML de comentário humano renderizado sem escape/sanitização.

## 9. Estratégia obrigatória de testes

### 9.1 Ciclo de trabalho

Para cada mudança:

1. escrever teste que falha pelo motivo esperado;
2. implementar a menor alteração correta;
3. executar teste focado;
4. refatorar;
5. executar toda a suíte Java;
6. executar Playwright relacionado contra backend real;
7. antes da entrega, executar toda a suíte sequencialmente.

### 9.2 Camadas

**Unidade:** validação de domínio, máquina de estados, hashes, orçamento de contexto, parser de resposta, criptografia/envelope e agendamento.  
**Integração:** Spring + SQLite temporário + Flyway; sem mocks para persistência. Ollama simulado apenas para falhas/protocolos determinísticos.  
**Contrato:** schemas versionados de API, resposta do modelo e pacote do professor.  
**E2E local:** Playwright com backend e SQLite reais; pode usar provider determinístico explicitamente identificado quando a qualidade da IA não está sob teste.  
**E2E Gemma:** opt-in, executado numa máquina com lock aprovado; sem interceptar API e sem aceitar nota exata como único oráculo.  
**E2E professor:** estudante exporta/publica, navegador do professor corrige, estudante importa/recebe e encontra a revisão após reinício.  
**Instalador:** VM limpa por SO, primeira instalação, retomada de download, sem rede, pouco disco, upgrade, downgrade recusado e desinstalação preservando dados.  
**Recuperação:** interrupção durante avaliação, migração e importação; banco continua consistente.

### 9.3 Fluxos Playwright mínimos

- onboarding e diagnóstico do Gemma;
- criar perfil local e alternar perfis sem vazamento;
- criar, salvar, editar e congelar revisão;
- avaliar com Gemma, acompanhar estados e abrir evidências;
- erro/timeout/contexto excessivo e retomada segura;
- histórico, progresso e comparação IA/humano;
- criar pedido para professor, cancelar, expirar e reenviar;
- professor corrige C1–C5 e devolve;
- estudante importa resposta, incluindo repetição e pacote adulterado;
- exportar backup, restaurar em instalação limpa e conferir histórico;
- teclado, foco, contraste, viewport móvel e leitor de tela básico;
- aplicação reiniciada mantém todos os dados.

## 10. Fases de implementação

### Fase 0 — congelar baseline e corrigir integridade

- registrar inventário e comportamento atual;
- criar testes de isolamento por perfil que inicialmente falham;
- corrigir todas as consultas por ID;
- tornar publicação/importação transacional;
- ligar apenas em loopback;
- corrigir cabeçalhos do frontend e estados desatualizados;
- separar E2E simulados de E2E integrados.

**Saída:** suíte verde, nenhum acesso cruzado entre perfis, falhas não deixam resultados parciais.

### Fase 1 — núcleo local-first

- resolver diretórios por sistema operacional;
- banco por perfil, migração do banco legado e backups consistentes;
- launcher com lifecycle do backend;
- token efêmero UI/API;
- importação/exportação da coleção inteira.

**Saída:** copiar/restaurar coleção em máquina limpa preserva todos os dados.

### Fase 2 — instalação Gemma sem configuração manual

- manifesto de componentes e checksums;
- instaladores por SO com Java incluído;
- instalação/descoberta do Ollama;
- download retomável do Gemma e smoke test;
- diagnóstico e opção Gemma menor;
- Docker movido para caminho avançado.

**Saída:** pessoa sem Java/Ollama/Docker instala e realiza a primeira correção.

### Fase 3 — consolidar avaliação e RAG

- contrato único de prompt/schema;
- cache limitado e persistente por fingerprint;
- correção dos fingerprints SQLite/WAL;
- isolamento rigoroso dos splits e deduplicação aproximada;
- avaliação humana congelada e gates de qualidade;
- auditoria completa por execução.

**Saída:** RAG aprovado pelos gates da seção 7 e E2E real Gemma reproduzível.

### Fase 4 — correção por professor via arquivo

- schemas versionados de pedido/resposta;
- exportação e importação cifrada, idempotente e transacional;
- modo Professor local na mesma distribuição;
- E2E completo entre dois perfis/bancos independentes.

**Saída:** professor corrige sem acesso ao banco do estudante e resposta sobrevive a reinício.

### Fase 5 — portal online e relay opaco

- SPA estática do professor;
- relay auto-hospedável de blobs com TTL;
- links de capacidade, expiração, revogação e uso único;
- polling/notificação local sem conta;
- threat model e teste de conteúdo inacessível ao servidor.

**Saída:** fluxo online completo; indisponibilidade do relay não bloqueia o fluxo por arquivo.

### Fase 6 — experiência de estudo estilo Anki

- cartões de treino derivados de competências e problemas;
- agenda local de revisão espaçada;
- metas, sessões e progresso explicável;
- exportação/importação dos cartões junto da coleção;
- acessibilidade e tutorial final.

**Saída:** ciclo criar → corrigir → reescrever → revisar funciona integralmente offline.

### Fase 7 — endurecimento e distribuição pública

- builds reprodutíveis e SBOM;
- assinatura/notarização de instaladores;
- atualizações assinadas com rollback seguro;
- matriz de hardware e benchmarks publicados;
- licença e avisos de terceiros revisados;
- documentação de contribuição, privacidade e recuperação.

## 11. Definition of Done global

Uma fase somente termina quando:

- critérios de saída foram automatizados sempre que possível;
- testes unitários, integração e E2E relevantes estão verdes em sequência;
- nenhuma rota crítica é comprovada apenas por mock de Playwright;
- banco antigo migra com backup e sem perda;
- logs e UI distinguem IA, humano e falha;
- documentação de instalação/recuperação foi testada em máquina limpa;
- limitações conhecidas e requisitos mínimos estão visíveis ao usuário;
- revisão de privacidade confirma que somente dados autorizados saem do computador.

## 12. Ordem imediata de execução

1. Implementar os testes vermelhos de isolamento entre perfis e transação.
2. Corrigir segurança local e ligar o servidor a loopback.
3. Corrigir regressões funcionais do frontend e criar E2E integrados.
4. Extrair o protocolo de pacote humano antes de criar qualquer portal remoto.
5. Criar launcher e diretórios portáveis.
6. Automatizar instalação Ollama/Gemma e testar em máquinas limpas.
7. Endurecer o RAG e congelar benchmark humano.
8. Implementar relay opaco somente depois do fluxo por arquivo estar aprovado.

## 13. Fora de escopo

- cobrança, assinatura, anúncios ou monetização;
- contas centralizadas de estudantes;
- painel escolar com acesso global a alunos;
- sincronização obrigatória de histórico;
- treinamento automático com redações privadas;
- dependência obrigatória de provedor de IA em nuvem.
