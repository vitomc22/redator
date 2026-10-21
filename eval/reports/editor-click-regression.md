# Clique perdido no editor — 20/10/2026

O botão “Continuar para inspeção” podia receber a pressão do ponteiro, mover-se durante o foco e deixar de receber a soltura. A página permanecia em `#new`, sem enviar a tentativa. O problema intermitente reapareceu na suíte completa após as primeiras execuções sem reprodução.

## Reprodução e causa

O teste manteve o botão pressionado por 100 ms, uma duração normal de interação. Os três envios dessa rodada falharam; os três testes de validação de campos passaram. A [captura de eventos](../results/editor-click-regression.json) mostra:

- pressão em `#continue`, com o botão entre y=416,19 e y=470,33;
- ponteiro mantido em y=443,25;
- rolagem automática de foco entre a pressão e a soltura, deslocando o botão para y=467,33;
- soltura e clique fora do botão, sem requisição para `/api/attempts`.

A regra global `html{scroll-behavior:smooth}` animava essa rolagem de foco. Ela foi alterada para `auto`, evitando que o botão se mova durante a pressão. Os usos explícitos de `scrollIntoView({behavior:'smooth'})` continuam disponíveis. Nenhum handler de envio ou contrato de API foi modificado.

## Verificação

O mesmo teste, com o mesmo clique de 100 ms e sem aumentar o timeout ou acrescentar retry, passou nos três envios após a alteração; os três casos de campos obrigatórios também passaram. Antes do navegador, passaram novamente 94 testes unitários e 32 de integração, com um live opt-in ignorado nessa execução offline.

A suíte Playwright completa passou com 20 testes em 56 segundos depois da correção, incluindo os fluxos com backend e SQLite reais.

Os testes agora anexam eventos de ponteiro/rolagem quando falham, e a configuração preserva traces em qualquer falha, mesmo sem retry. Uma coleta anterior foi descartada porque as execuções de diagnóstico se sobrepuseram; ela não é evidência de validação.
