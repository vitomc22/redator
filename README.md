# Corretor ENEM IA

Projeto MVP para avaliação automatizada de redações do ENEM com foco em: arquitetura enxuta, medição objetiva, revisão humana obrigatória antes da avaliação e documentação de qualidade do pipeline.

O repositório já cobre a maior parte do fluxo funcional do plano descrito em [plano_corretor_enem_ia_mvp_v2.md](plano_corretor_enem_ia_mvp_v2.md), com foco em um monólito Java + Spring Boot simples e executável localmente.

---

## Status atual

### Implementado

- backend Spring Boot em Java 21
- SQLite + Flyway
- API REST para criação, revisão, avaliação e benchmark
- avaliação por perfil (`cheap`, `normal`, `premium`)
- gate de revisão humana para documentos e uploads
- validação de PDF/JPG/PNG
- qualidade de transcrição com CER/WER/preservation e detecção de ruído OCR
- comparação de baselines e métricas de dataset
- frontend estático com revisão visual, destaque de trechos suspeitos e preview de documento

### Fase ativa

A fase ativa do roadmap é a etapa de entrada documental realista do F3:

- calibração da qualidade de OCR/PDF e imagens
- reforço do gate de revisão humana para ruído documental
- melhoria da experiência de revisão com blocos editáveis e destaque de trechos suspeitos
- ajuste de ruído curto de OCR (sequências de teclado e tokens consonantais sem vogais)

O projeto já passou do MVP texto-only e está validando o passo seguinte: fluxo de imagem/PDF com revisão e qualidade documental mais realista.

---

## Fluxo funcional atual

### Texto direto

1. o usuário envia texto e tema
2. a redação é persistida
3. a avaliação pode seguir sem revisão humana

### Upload de PDF / imagem

1. o arquivo é validado por tipo e conteúdo
2. a transcrição literal é extraída/gerada pelo gateway de visão
3. a redação apenas entra no pipeline após revisão humana
4. o editor pode aprovar ou rejeitar o texto
5. só depois da revisão o sistema libera a avaliação final

### Gate de qualidade documental

A regra atual considera:

- CER
- WER
- preservation rate
- presença de ruído OCR/gibberish em texto revisado

Se o texto tiver sinal de transcrição comprometida, a redação continua em `NEEDS_REVIEW` mesmo que os números gerais pareçam aceitáveis.

---

## Arquitetura

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

- `EssayController`: endpoints da API
- `EssayService`: regras de negócio, review gate e persistência
- `DocumentTranscriptionService`: análise de documento, suspeitas e revisão
- `VisionTranscriptionGateway`: fronteira para OCR/visão
- `TranscriptionQualityMetricsCalculator`: gate documental
- `DatasetMetricsCalculator`: métricas do dataset
- `BaselineComparisonService`: comparação e benchmark

---

## Endpoints principais

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
POST /api/benchmark
```

### Comparação de perfis

```http
POST /api/benchmark/compare
```

### Qualidade da transcrição

```http
GET /api/essays/{id}/quality
```

---

## Roadmap em sequência

### Fase 0 — concluída

- projeto Spring Boot e estrutura local
- SQLite + Flyway
- gateway LLM, cache e budget guard
- schema validation
- baselines e métricas
- CI e testes básicos

### Fase 1 — concluída

- pipeline texto → nota
- rubricas por competência
- perfis de avaliação
- persistência e API pública

### Fase 2 — concluída

- dataset benchmarks e relatórios
- comparação de baselines
- comparação entre profiles
- validação de qualidade e review gate

### Fase 3 — em andamento

- OCR/PDF e qualidade documental
- revisão humana com destaque de trechos suspeitos
- refinamento da experiência de edição
- guardar critérios de go/no-go com dados documentais reais

### Fase 4 — pendente

- endurecimento operacional
- segurança, LGPD e retenção
- suite adversarial e E2E
- README/report final e limites do MVP

---

## Como rodar localmente

### Requisitos

- Java 21+
- Maven 3.8+
- Git

### Executar a aplicação

```bash
cd /home/victor/Documentos/git/redator
mvn spring-boot:run
```

A aplicação fica em:

```text
http://localhost:8080/
```

### Rodar testes

```bash
cd /home/victor/Documentos/git/redator
mvn test
```

---

## Estrutura do projeto

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
├── sample-data/
├── src/main/resources/static/index.html
├── README.md
├── plano_corretor_enem_ia_mvp_v2.md
├── pom.xml
└── target/
```

---

## Observações importantes

- este projeto é um MVP experimental e não substitui a correção oficial do ENEM
- a revisão humana é obrigatória para textos originados em imagem/PDF
- o sistema prioriza evidência, rastreabilidade e medida objetiva sobre sofisticação de infraestrutura
- a próxima etapa de desenvolvimento é a calibração da qualidade documental e a refinamento da revisão visual

---

## Verificação atual

A suíte de testes do projeto foi validada com sucesso com:

```bash
cd /home/victor/Documentos/git/redator
mvn -q test
```

Resultado verificado: `EXIT:0`.

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
