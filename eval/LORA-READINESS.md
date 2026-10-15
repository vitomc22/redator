# LoRA: fase posterior, ainda não habilitada

Referência: [plano v3](../plano_corretor_enem_ia_mvp_v2.md), seção 10.

Não há treino, contratação de GPU ou promoção de adaptador nesta entrega.
Primeiro medir o Gemma E2B base no mesmo runtime e quantização usados no produto.

## Evidências exigidas antes do treinamento

- Relatório do baseline real com falhas por competência, cobertura e latência.
- Direitos explícitos para **training**, independentes da permissão de avaliação.
- Texto original, proposta e cinco notas revisadas, com justificativas e evidências literais.
- Separação por grupos temáticos, duplicatas e autor quando conhecido.
- Configuração congelada e teste final ainda não usado para ajustar prompts.
- Suporte demonstrado à revisão exata do Gemma 4 E2B em Transformers/PEFT e na exportação para Ollama.

## Formato proposto (não contém exemplos humanos inventados)

Cada registro de treino contém `id`, `split`, `themeGroup`, `textSha256`,
`promptSha256`, `labelRevision`, `rightsStatus`, `allowedUses` e `messages`.
As mensagens seguem o mesmo system prompt e objeto de usuário do runtime;
o assistant contém o JSON validado de cinco competências. Identificadores,
autores e notas humanas de referência nunca entram na mensagem do usuário.
Preservar erros e parágrafos. Não truncar silenciosamente.

## Prova de compatibilidade antes de gasto significativo

1. Carregar pesos oficiais instruction-tuned, não o GGUF do runtime.
2. Aplicar LoRA e demonstrar quais módulos recebem gradiente.
3. Executar um passo, salvar e recarregar o adaptador.
4. Exportar, quantizar em Q4_K_M e importar no Ollama.
5. Verificar o mesmo contrato JSON e comparar base/adaptado antes e depois da quantização.

Registrar versões exatas, seeds, hashes e consumo medido. GPU de 16–24 GB é
apenas candidata, não garantia. Promoção depende de ganho validado, sem
regressão de evidências, memória, latência ou estabilidade. Manter base para rollback.
