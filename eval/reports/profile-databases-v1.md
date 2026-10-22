# Bancos ativos por perfil — 21/10/2026

O aplicativo usa `profiles/<id>/redator.sqlite` por padrão. O datasource segue a identidade local da requisição e o Spring conserva a conexão durante cada transação. Cada banco recebe as migrações Flyway e os temas iniciais antes da primeira consulta. O cache de avaliações também fica no banco do perfil.

## Migração e restauração

Quando há um `redator.db` legado, a inicialização prepara um snapshot e as coleções privadas com o exportador já validado. Antes de ativar, verifica os hashes dos arquivos preparados e a impressão lógica do legado, incluindo dados comprometidos no WAL. O arquivo `runtime/profile-migration-active.json` identifica a ativação concluída; reinícios posteriores preservam edições nas coleções privadas. Uma ativação interrompida pode continuar quando as cópias existentes ainda coincidem com o manifesto. Colisões e alterações no legado interrompem a inicialização sem substituir arquivos divergentes.

Bancos anteriores ao V10 são atualizados somente na cópia de trabalho. `legacy-source.sqlite` preserva o schema anterior e seus registros; `legacy-snapshot.sqlite` recebe as migrações antes da separação. Um teste reproduziu a falha com V5 antes da correção e passou a verificar a origem intacta, o snapshot V5 e os bancos privados V10. A [verificação do banco V5 existente](../results/legacy-v5-migration-v1.json) confirmou todos os registros das dez tabelas no snapshot bruto e preservou 721 redações distribuídas entre dois perfis, sem modificar o arquivo versionado.

A origem e o snapshot completo permanecem disponíveis. Dados legados sem proprietário verificável continuam somente no snapshot, conforme o [protocolo do exportador](profile-collections-v1.md).

O backup exporta somente o banco do perfil ativo. A importação exige o schema V1–V10 conhecido, integridade, relações válidas e o mesmo proprietário. O arquivo validado fica no diretório do perfil até o próximo início; antes da primeira conexão, a restauração salva uma cópia de segurança e substitui somente aquela coleção.

`REDATOR_PROFILE_DATABASES=false` mantém o modo legado para compatibilidade. Os bancos privados e o legado não são sincronizados; alternar gravações entre esses modos pode produzir divergência, que a ativação detecta e recusa.

## Regressões reproduzidas e corrigidas

- O benchmark inseria um tema em uma conexão e consultava `last_insert_rowid()` em outra. Isso produzia uma referência inválida no datasource sem pool. A chave agora é obtida da própria operação de inserção, com um teste Spring/SQLite no modo por perfil.
- A restauração verificava o proprietário sem rejeitar tabelas desconhecidas ou relações quebradas. Reutiliza agora a validação completa do exportador. O teste altera um snapshot válido e confirma a recusa, a ausência de arquivo preparado e a preservação da coleção ativa.
- O launcher podia escolher um JAR histórico pela ordenação dos nomes. Agora seleciona o artefato atual, o pacote `app/redator.jar` ou o caminho configurado explicitamente, e resolve o corpus a partir do diretório do aplicativo.
- Um pacote chamado `app/redator.jar` não era reconhecido pelos comandos `status` e `stop`. O launcher registra e verifica o caminho exato do JAR, inclusive em diretórios com espaços; o teste mantém um processo controlado e verifica seu encerramento.
- Na primeira compilação, mensagens do Maven podiam ser capturadas como parte do caminho do JAR. O teste executa um compilador controlado que imprime um log e confirma que o artefato correto é iniciado; diretórios de dados relativos são resolvidos em relação ao diretório de quem chamou o launcher.
- O SBOM era gerado na fase `package`, depois da montagem do JAR. A geração passou para `generate-resources`, e `npm run test:package` inspeciona o artefato executável para verificar o SBOM e detectar classes/configurações desatualizadas.
- O fluxo Gemma real com a política padrão falhou antes da inferência: três referências excediam o orçamento de contexto. A redução auditada de referências passou a valer também para `lexical-v1`, conservando o texto integral e ao menos uma referência. O teste de orçamento foi estendido às duas políticas e reproduziu a falha antes da correção. O preflight da fixture real passou com duas referências, uma omitida e estimativa de 6092 tokens de entrada dentro do contexto 8192. O teste de navegador também passou a verificar a resposta HTTP da avaliação imediatamente, evitando aguardar o timeout da navegação quando o backend já informou uma falha.

## Verificação

Os novos testes foram executados antes das correções: duas falhas em `ProfileDatabaseIntegrationTest`, duas reproduções separadas em `LocalLauncherContractTest`, a inspeção do pacote e o caso V5 em `ProfileMigrationIntegrationTest` reproduziram os problemas descritos. A integração Java Gemma permanece opt-in e não integra a rodada offline.

A suíte Java de compatibilidade usa o banco legado; `ProfileDatabaseIntegrationTest` e os fluxos Playwright usam o modo por perfil. A pipeline CI executa unitários, integrações, configuração/cliente HTTP, Playwright e falha real de transporte em sequência, preservando evidências de falhas do navegador.

A rodada final após as correções V5/contexto aprovou 108 testes unitários, 54 integrações offline, seis testes Node de configuração, quatro do cliente HTTP e um de inspeção do pacote. O JAR contém o SBOM CycloneDX 1.6 com 54 componentes de runtime, além das classes e recursos comparados com a compilação atual.

Passaram 23 cenários Playwright e um cenário separado de falha real de conexão, com backend e SQLite reais nos fluxos de integração. O [teste do JAR pelo launcher](../results/profile-launcher-smoke-v1.json) iniciou o aplicativo a partir de `/tmp`, criou duas coleções com IDs de redação iguais, exportou/preparou um backup, encerrou e reiniciou o processo. A coleção de Alice foi restaurada, a de Bob foi preservada e uma cópia de segurança foi conferida. O runtime permaneceu Ollama/Gemma; esse teste de restauração não realizou inferência.

O [fluxo de navegador do pacote final](../results/profile-packaged-live-v1.json) passou em Java 21.0.12.1, com JAR, SQLite por perfil e Gemma reais: uma chamada sem cache, cinco competências publicadas, evidências literais e auditoria persistida. A política padrão `lexical-v1` usou duas referências e registrou uma omitida. A [reprodução antes/depois](../results/default-context-regression-v1.json) conserva a falha `CONTEXT_LIMIT` anterior, sem chamada IA nem nota, e a execução corrigida. O teste de inspeção também conferiu o SBOM e a classe do provider atual dentro do JAR.

O resultado funcional foi 1000 contra a referência humana 800: erro de 200 pontos. Portanto, a passagem do navegador não aprova qualidade pedagógica. O gate de release padrão permanece reprovado em C2, C5 e QWK, e o [piloto novo](grounded-v3.md) também reprovou C5.

No total, passaram 162 testes Java, 11 testes Node e 25 cenários de navegador, incluindo a falha real de conexão e a geração real no JAR. A integração Java live e a inspeção de resultado já existente do navegador permanecem opt-in/condicionais e foram ignoradas nesta rodada. Os [metadados da validação](../results/profile-validation-v1.json) registram as contagens, o SHA-256 e os artefatos.
