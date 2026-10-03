# Corretor ENEM IA

Projeto de MVP para avaliação automatizada de redações do ENEM, com foco em arquitetura enxuta, medição objetiva e fluxo de revisão humana antes da avaliação final. O objetivo é transformar uma redação em uma nota estimada com evidências, riscos, métricas e persistência do histórico de execução.

Este repositório implementa a base funcional do plano descrito em [plano_corretor_enem_ia_mvp_v2.md](plano_corretor_enem_ia_mvp_v2.md), mantendo o MVP robótico, sem microserviços e com baixa infraestrutura.

---

## 1. Visão geral

O sistema hoje já cobre os elementos centrais da arquitetura planejada:

- entrada de texto e upload de arquivo
- revisão humana obrigatória para texto originado em imagem/PDF
- avaliação por perfil (`cheap`, `normal`, `premium`)
- persistência local em SQLite
- métrica de qualidade de transcrição
- comparação e relatórios de dataset
- fluxo de benchmark e comparação de baselines

O produto continua sendo um MVP experimental, não um substituto da correção oficial do ENEM.

---

## 2. Status do projeto

### Implementado

- backend Spring Boot em Java 21
- SQLite + Flyway
- API REST para criação, revisão e avaliação de redações
- gatilho de `NEEDS_REVIEW` para redações de upload/documento
- validação de upload de PDF/JPG/PNG
- qualidade de transcrição com tokens suspeitos e análise de CER/WER/preservação
- benchmark de dataset e comparação entre modelos/baselines
- frontend estático com fluxo de revisão humana

### Fase atual

A etapa ativa do plano é a integração do pipeline documental com um provedor real de OCR/visão, sem quebrar o gate humano anterior à avaliação. O código já separa bem a camada de transcrição e o gateway, deixando o ponto de extensão limpo para integração com um OCR real.

---

## 3. Arquitetura

```text
Browser / UI
      |
      v
Spring Boot App
      |
      +--> EssayController
      |
      +--> EssayService
      |      |
      |      +--> DeterministicEssayAnalysis
      |      +--> RubricEngine
      |      +--> LlmGateway
      |      +--> DocumentTranscriptionService
      |      +--> TranscriptionQualityMetricsCalculator
      |
      +--> SQLite (arquivo local)
             |
             +--> tema
             +--> essay
             +--> run
             +--> competence_result
             +--> llm_call
```

### Componentes principais

- `EssayController`: endpoints públicos para criação, upload, revisão e avaliação
- `EssayService`: orquestração de regras de negócio, persistência e gate de revisão
- `DocumentTranscriptionService`: validação, análise e transcrição literal de documentos
- `VisionTranscriptionGateway`: seam de integração para provedor de visão/OCR
- `TranscriptionQualityMetricsCalculator`: CER, WER, preservation rate e go/no-go
- `DatasetMetricsCalculator`: cálculo de QWK, MAE, RMSE, acordo exato e adjacente
- `BaselineComparisonService`: comparação de modelos e baselines

---

## 4. Fluxo funcional atual

### 4.1 Texto direto

1. usuário envia texto e tema
2. a redação é salva como `READY`
3. a avaliação pode ir direto para o pipeline sem revisão humana

### 4.2 Upload de PDF / imagem

1. arquivo é validado por tamanho e magic bytes
2. a transcrição literal é extraída ou produzida pelo gateway de visão
3. o texto não é aceito como válido sem revisão humana
4. o editor revisa o texto e decide aprovar ou rejeitar
5. só depois da revisão o sistema permite a avaliação final

### 4.3 Gate de qualidade

O gate humano depende de métrica de qualidade de transcrição. A lógica atual compara o texto revisado com o texto extraído e valida:

- CER
- WER
- preservation rate
- presença de tokens suspeitos

Se a qualidade não for suficiente, a redação continua em `NEEDS_REVIEW`.

---

## 5. Roadmap em fases

### Fase 1 — infraestrutura local

- Java + Spring Boot
- SQLite + Flyway
- tema, essay, run, competence_result, llm_call

### Fase 2 — avaliação de redação

- análise determinística
- rubrica por competência
- perfis de custo e latência
- persistência do resultado

### Fase 3 — benchmark e qualidade

- métricas de dataset
- QWK, MAE, RMSE, agreement, bias
- comparação de baselines
- relatórios em Markdown/CSV

### Fase 4 — documento e revisão humana

- upload de PDF/imagem
- validação documental
- transcrição literal
- marcação de tokens suspeitos
- revisão humana obrigatória antes da avaliação

### Fase 5 — OCR/visão real

- prover um backend documental real (OCR/vision)
- manter a revisão humana como gate obrigatório
- registrar métricas de qualidade da transcrição e custo por página

---

## 6. Principais endpoints

### Temas

```http
GET /api/temas
```

### Criar redação em texto

```http
POST /api/essays
Content-Type: application/json

{
  "temaId": 1,
  "text": "A educação é um direito fundamental"
}
```

### Criar redação por upload

```http
POST /api/essays
Content-Type: multipart/form-data
```

Campos esperados:

- `temaId`
- `text` (opcional)
- `file` (PDF/PNG/JPG)

### Revisão humana

```http
POST /api/essays/{id}/review
Content-Type: application/json

{
  "reviewedText": "Texto revisado pelo editor",
  "approved": true
}
```

### Avaliação

```http
POST /api/essays/{id}/evaluate?profile=cheap
```

### Benchmark

```http
POST /api/essays/benchmark
```

### Comparação de perfis

```http
POST /api/essays/compare
```

### Qualidade da transcrição

```http
GET /api/essays/{id}/quality
```

---

## 7. Fluxo de avaliação e revisão

A regra atual do sistema é simples e explícita:

- texto puro = pode ser avaliado
- upload/documento = precisa de revisão humana
- revisão aprovada + qualidade de transcrição aceitável = `READY`
- revisão rejeitada ou qualidade ruim = `NEEDS_REVIEW`

Isso evita conflitar o erro de OCR/comportamento do modelo com o erro de avaliação da redação.

---

## 8. Estrutura do projeto

```text
redator/
├── src/
│   ├── main/
│   │   ├── java/com/redator/corretor/
│   │   │   ├── controller/
│   │   │   ├── model/
│   │   │   └── service/
│   │   └── resources/
│   │       ├── static/
│   │       └── db/migration/
│   └── test/java/com/redator/corretor/
├── data/
├── eval/
├── sample-data/
├── README.md
├── plano_corretor_enem_ia_mvp_v2.md
├── pom.xml
└── target/
```

---

## 9. Como rodar localmente

```bash
cd /home/victor/Documentos/git/redator
mvn spring-boot:run
```

Depois, abra a interface em:

```text
http://localhost:8080/
```

ou use os endpoints diretamente pela API.

---

## 10. Como validar

```bash
cd /home/victor/Documentos/git/redator
mvn -q -Dtest=DatasetMetricsCalculatorTest,EssayControllerIntegrationTest test
```

A validação atual cobre:

- métricas de dataset
- comparação de baselines
- qualidade da transcrição
- gate de revisão humana
- upload de documento
- avaliação de fluxo completo

---

## 11. Próximo passo do plano

A próxima etapa do roadmap é a integração de um provedor real de OCR/visão para PDF e imagem, preservando a arquitetura atual:

- manter `VisionTranscriptionGateway` como fronteira
- facilitar a troca de provider sem mexer em regras de negócio
- continuar exigindo revisão humana antes da avaliação
- medir CER/WER e preservação antes de aceitar a transcrição como final

Esse é o ponto exato em que o MVP deixa de ser apenas um fluxo local de texto e passa a cobrir o problema real de entrada documental do ENEM.


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
