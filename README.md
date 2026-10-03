# Corretor ENEM IA

Projeto de MVP para avaliação automatizada de redações do ENEM, com arquitetura simples, local, reutilizável e orientada a evidência. O objetivo principal é transformar uma redação em uma nota estimada, com análise por competência, indicadores de risco e persistência do histórico de execução.

Este repositório implementa a base funcional do plano de desenvolvimento descrito em [plano_corretor_enem_ia_mvp_v2.md](plano_corretor_enem_ia_mvp_v2.md), mantendo foco em um monólito Java + Spring Boot com SQLite e sem dependências pesadas de infraestrutura.

---

## 1. Visão geral

O sistema recebe uma redação em texto e a avalia em cinco competências, seguindo a lógica do ENEM, mas com um modelo experimental e auditável. A solução foi pensada para funcionar em ambiente local de desenvolvimento sem GPU e sem infraestrutura extra.

### Objetivo principal

- receber uma redação
- identificar traços estruturais e problemas
- classificar a competência pela rubrica
- produzir uma nota total estimada
- registrar evidências, problemas e histórico de execução
- permitir comparação entre perfis de avaliação

### Não objetivo do MVP

- substituir a correção oficial do ENEM
- exigir microserviços ou Kubernetes
- depender de banco externo ou filas assíncronas
- montar um produto multiusuário pronto para produção
- processar OCR/PDF como funcionalidade central no início

---

## 2. Arquitetura do MVP

A solução foi pensada como monólito modular em um único processo Java.

```text
Cliente / Browser
      |
      v
Spring Boot App
      |
      +--> Controller REST
      |
      +--> EssayService
      |      |
      |      +--> DeterministicEssayAnalysis
      |      +--> RubricEngine
      |      +--> LlmGateway
      |
      +--> SQLite (arquivo local)
             |
             +--> tema
             +--> essay
             +--> run
             +--> competence_result
             +--> llm_call
             +--> human_score
```

### Componentes principais

- Spring Boot: API REST e orquestração do fluxo
- SQLite: persistência local leve
- Flyway: migração do schema
- JdbcTemplate: acesso ao banco
- DeterministicEssayAnalysis: regras livres e verificações automáticas
- RubricEngine: cálculo da nota por competência
- LlmGateway: camada para simular/provider de modelo
- EvaluationProfile: perfis `cheap`, `normal`, `premium`

---

## 3. Entregas do MVP

O MVP cobre as seguintes capacidades:

1. criação de redação com tema associado
2. atualização do texto da redação
3. execução de análise determinística
4. cálculo por competências C1 a C5
5. avaliação por perfil de custo/latência
6. persistência de execução e resultados
7. histórico de runs e detalhes por competência
8. endpoints REST para consumo simples
9. frontend estático para interação rápida

---

## 4. Fluxo funcional

### Fluxo principal

1. Usuário cria uma redação com tema e texto.
2. O sistema valida campos obrigatórios.
3. A redação é salva no banco.
4. O endpoint de avaliação aceita o perfil de execução.
5. O serviço realiza análise determinística.
6. O motor de rubrica calcula nota por competência.
7. O gateway registra a chamada do provedor/modelo.
8. O resultado final é salvo em `run` e `competence_result`.
9. A API responde com `runId`, `total`, `needsReview` e competências.

### Regras de análise

A análise determinística cobre:

- contagem de palavras
- número de parágrafos e linhas
- detecção de texto muito curto
- sinais de oralidade ou estilo informal
- detecção de instruções externas ou prompt injection
- resumo estrutural da redação para evidência

---

## 5. Rubrica implementada

A avaliação atual funciona em um modelo híbrido simples:

- C1: domínio da modalidade escrita formal
- C2: compreensão da proposta e tipo textual
- C3: seleção, relação e organização de argumentos
- C4: mecanismos de coesão e organização textual
- C5: proposta de intervenção

A lógica usa uma combinação de:

- heurísticas determinísticas
- sinais de estrutura textual
- avaliação mínima por competência
- marcação de revisão obrigatória quando há risco

> O MVP não pretende ser um modelo acadêmico final. Ele fornece um laboratório funcional para medir, calibrar e evoluir a partição de avaliação mais tarde.

---

## 6. Perfis de avaliação

O projeto inclui o enum `EvaluationProfile`, com os perfis abaixo:

- `cheap`: menor custo, menor latência
- `normal`: custo intermediário
- `premium`: maior custo, mais robustez e observabilidade

A API aceita esse perfil via query param:

```http
POST /api/essays/{id}/evaluate?profile=cheap
```

Se o perfil não existir, a API devolve erro HTTP 400 com mensagem clara.

---

## 7. Estrutura do projeto

```text
redator/
├── data/
│   └── redator.db
├── src/
│   ├── main/
│   │   ├── java/com/redator/corretor/
│   │   │   ├── config/
│   │   │   │   └── InitialDataLoader.java
│   │   │   ├── controller/
│   │   │   │   ├── EssayController.java
│   │   │   │   └── GlobalExceptionHandler.java
│   │   │   ├── model/
│   │   │   │   ├── CompetenceResult.java
│   │   │   │   ├── CreateEssayRequest.java
│   │   │   │   ├── EssayResponse.java
│   │   │   │   ├── EvaluationProfile.java
│   │   │   │   ├── EvaluationResult.java
│   │   │   │   └── Tema.java
│   │   │   └── service/
│   │   │       ├── DeterministicEssayAnalysis.java
│   │   │       ├── EssayService.java
│   │   │       ├── LlmCallResult.java
│   │   │       ├── LlmGateway.java
│   │   │       ├── RubricEngine.java
│   │   │       └── CorretorEnemApplication.java
│   │   └── resources/
│   │       ├── application.properties
│   │       ├── static/
│   │       │   └── index.html
│   │       └── db/migration/
│   │           └── V1__init.sql
│   └── test/
│       └── java/com/redator/corretor/
│           └── EssayControllerIntegrationTest.java
├── plano_corretor_enem_ia_mvp_v2.md
├── pom.xml
├── README.md
└── target/
```

---

## 8. Banco de dados

Os dados ficam em um arquivo SQLite local em:

```text
data/redator.db
```

### Tabelas principais

- `tema`: temas disponíveis
- `essay`: redações criadas
- `run`: registros de execução de avaliação
- `competence_result`: nota por competência
- `llm_call`: metadados da chamada ao modelo
- `human_score`: notas humanas futuras

### Migração inicial

A migração está em:

- [src/main/resources/db/migration/V1__init.sql](src/main/resources/db/migration/V1__init.sql)

O Flyway é responsável por garantir que o schema exista e seja consistente.

---

## 9. API REST

### Temas

#### GET /api/temas

Retorna a lista de temas cadastrados.

### Redação

#### POST /api/essays

Cria uma redação.

Body exemplo:

```json
{
  "temaId": 1,
  "text": "A educação é um direito fundamental..."
}
```

#### PUT /api/essays/{id}/text

Atualiza o texto da redação.

#### POST /api/essays/{id}/evaluate?profile=cheap

Executa a análise e retorna o resumo da run.

#### GET /api/essays/{id}/result

Retorna o resultado mais recente da redação.

#### GET /api/runs/{id}

Retorna os detalhes da execução.

---

## 10. Exemplo de execução

### Requisitos

- Java 21+
- Maven 3.8+
- Linux/macOS/Windows com terminal

### Executar localmente

```bash
cd /home/victor/Documentos/git/redator
mvn spring-boot:run
```

A aplicação fica disponível em:

```text
http://localhost:8080
```

### Rodar testes

```bash
mvn test
```

---

## 11. Frontend

A interface é uma página estática servida pelo próprio Spring Boot, em:

- [src/main/resources/static/index.html](src/main/resources/static/index.html)

Ela permite:

- escolher tema
- enviar texto da redação
- disparar avaliação
- visualizar resultado simplificado

O objetivo do frontend é servir como prova de conceito e não como aplicação completa.

---

## 12. Plano de evolução do projeto

O plano completo está em [plano_corretor_enem_ia_mvp_v2.md](plano_corretor_enem_ia_mvp_v2.md). Abaixo está um resumo das fases previstas.

### Fase 1 — base funcional

- backend REST
- SQLite + Flyway
- análise determinística
- rubrica por competência
- página estática
- testes de integração básicos

### Fase 2 — gateway e orquestração

- perfis de avaliação
- provider fake + provider real
- cache por hash de prompt
- budget guard
- schema validation
- registro de custo e latência

### Fase 3 — entrada multimodal

- PDF e imagem
- transcrição literal
- revisão humana obrigatória
- destacando tokens suspeitos

### Fase 4 — medição e benchmarking

- dataset de avaliação
- métricas como QWK, concordância adjacente, viés médio
- comparação entre modelos
- relatório de custo e latência

### Fase 5 — consolidação e expansão

- melhor rubrica calibrada
- entrada e validação de tema em escala
- logs estruturados e observabilidade
- preparação para robustez operacional

---

## 13. Principais riscos e guardrails

### 1. Prompt injection

Redações podem incluir instruções externas que tentem manipular a interpretação do sistema. O código já faz detecção simples de padrões suspeitos.

### 2. Texto muito curto

Obras muito curtas tendem a receber nota baixa para evitar falsa precisão.

### 3. Oralidade e informalidade

O sistema marca elementos de registro informal como possíveis indicadores de perda de formalidade.

### 4. Custo de LLM

Mesmo em um MVP, o custo de chamadas de API pode crescer rápido. Por isso o projeto trabalha com perfil e guardas de orçamento.

### 5. Revisão humana

Quando o modelo ou a estrutura do texto geram risco, a aplicação sinaliza `needsReview`, evitando falsa sensação de confiabilidade.

---

## 14. Observabilidade e auditoria

A persistência em `llm_call` e `run` permite rastrear:

- modelo/provider
- prompt hash
- schema hash
- custo
- latência
- status da chamada
- histórico de execução

Essa persistência é essencial para medir qualidade, custo e estabilidade do sistema.

---

## 15. Status atual do repositório

O repositório já está em um estado funcional de MVP:

- projeto Spring Boot configurado
- SQLite funcionando
- Flyway validado
- API REST ativa
- frontend básico entregue
- testes de integração funcionando
- fluxo de avaliação com perfis implementado

---

## 16. Próximos passos recomendados

A sequência lógica sugerida para continuar o desenvolvimento é:

1. validar schema de respostas do LLM
2. implementar proteção de orçamento
3. criar CLI de benchmark por dataset
4. inserir revisão humana de textos transcritos
5. evoluir rubrica e métricas de performance
6. introduzir provider real (cloud ou local)
7. preparar relatórios comparativos de qualidade e custo

### CLI de benchmark por dataset

O repositório agora inclui um runner de benchmark em lote para rodar uma coleção de redações e obter um resumo consolidado de custo, latência e nota média.

Execução:

```bash
mvn -q -DskipTests compile
mvn -q -DskipTests exec:java \
  -Dexec.mainClass=com.redator.corretor.cli.BenchmarkDatasetCli \
  -Dexec.args="sample-data/benchmark-sample.json 1 cheap"
```

O arquivo JSON pode ser uma lista de textos ou um objeto com a chave `texts`.

---

## 17. Conclusão

Este projeto é um ponto de partida robusto para um corretor automatizado de redações ENEM, com foco em:

- simplicidade
- baixo custo
- reprodutibilidade
- observabilidade
- evolução guiada por dados

A estrutura atual fornece a base necessária para crescer em direção a um sistema de correção mais sofisticado, mantendo o princípio central do plano: o sistema deve coletar evidências, aplicar uma rubrica auditável e permitir calibração com dados humanos.

---

Se você quiser, posso continuar em duas direções agora:

1. implementar a próxima etapa do plano de arquitetura (schema validation + budget guard), ou
2. ampliar o README com uma seção de roadmap executiva, screenshots e exemplos de payload/response mais completos.
