# TODO — próximas tarefas

Pendências em aberto do AeroTalks. Todo item pendente entra aqui e ganha uma marca `// TODO:` (ou `<!-- TODO: -->`) no ponto do código, terminando com "ver TODO.md". Ao resolver um item, remova também a marca no código.

## Reenvio quando um vizinho aparecer

Mensagens salvas sem rota (nenhum vizinho com sinal, ou o vizinho escolhido está fora de alcance) ficam pendentes, e o rótulo na lista comunica "reenvio quando vizinho aparecer" (decidido). Falta implementar o reenvio de fato: tentar enviar de novo quando um vizinho aparecer.

Onde mexe: `AgendaActivity.enviar()` e `BleMesh` (reenvio); `MensagemAdapter` (rótulo — já atualizado).

## Fora do escopo por decisão

Estes itens **não** são tarefas — ficaram de fora de propósito (protótipo acadêmico, escopo pequeno e funcional):

- Criptografia — o texto trafega em claro.
- Relay/multi-hop — a mensagem só alcança quem está no alcance direto.
- Anexos — só texto.
- Canais protegidos e filtro de mensagens por canal — canal é apenas um rótulo local.
