# Piloto de evidências distribuídas — 21/10/2026

O contrato opt-in `APP_LLM_CONTRACT=grounded-v2` oferece evidências literais do início, desenvolvimento e conclusão, com até 24 trechos. Inclui orientações explícitas sobre repertório produtivo em C2 e os elementos da intervenção em C5. A serialização é determinística e recebe uma identidade de prompt/cache própria. O contrato padrão permanece inalterado.

A [configuração congelada](../config/grounded-v3.json) selecionou três redações novas de validação após excluir as seis já utilizadas nos pilotos anteriores. As duas correções humanas individuais foram preservadas. Ambos os braços usam esse mesmo contrato: baseline sem recuperação e candidato com uma referência `train` da política `calibration-v2`. Portanto, a comparação mede o efeito da recuperação dentro do contrato novo; não isola o efeito do contrato em relação ao legado.

O baseline foi concluído na sessão anterior. O candidato foi executado nesta continuação usando o snapshot de classes congelado indicado na configuração. Os hashes das mensagens e dos schemas dos seis resultados foram reproduzidos com o código atual, sem novas inferências. O preflight inicialmente registrado cobre as 66 redações de validação; o [preflight específico do piloto](../results/grounded-pilot-preflight-v3.json) identifica exatamente esta população de três.

## Resultado

Os dois braços concluíram 3/3 `READY`, com uma geração real sem cache por redação. A [auditoria](../results/grounded-audit-v3.json) conferiu as notas contra os SQLite operacionais, os hashes das requisições e as três referências no split `train`, sem amostras reservadas.

| Medida | Baseline | RAG com uma referência |
| --- | ---: | ---: |
| MAE C1 | 26,6667 | 26,6667 |
| MAE C2 | 26,6667 | 26,6667 |
| MAE C3 | 60,0000 | 60,0000 |
| MAE C4 | 20,0000 | 20,0000 |
| MAE C5 | 80,0000 | 93,3333 |
| QWK agregado médio dos dois avaliadores | -0,147091 | 0,088274 |

O [gate](../results/grounded-gate-v3.json) **reprovou C5**, apesar da melhora do QWK agregado. O QWK de C1 é indefinido para um dos avaliadores no candidato, pois ambas as séries são constantes; o valor permanece registrado como `NaN`. O gate atual exige QWK agregado calculável, sem substituir os QWK por competência que não podem ser calculados.

Esta população pequena serve para diagnóstico. Não qualifica release, não equivale à execução completa das 66 redações e não foi usada para promover o contrato novo ou alterar o padrão. O teste reservado permanece necessário, e o gate do contrato padrão continua reprovado em C2, C5 e QWK.

Artefatos: [baseline](../results/grounded-baseline-v3.json), [candidato](../results/grounded-rag-v3.json), [seleção congelada](../frozen/public-validation-grounded-pilot-v3.json).
