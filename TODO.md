# TODO — próximas tarefas

Pendências em aberto do AeroTalks. Todo item pendente entra aqui e ganha uma marca `// TODO:` (ou `<!-- TODO: -->`) no ponto do código, terminando com "ver TODO.md". Ao resolver um item, remova também a marca no código.

## Rótulo de entrega ("aguardando repasse")

Quando uma mensagem é salva sem rota (nenhum vizinho com sinal, ou o vizinho escolhido está fora de alcance), ela fica marcada com "aguardando repasse" para sempre — mas o app não tem mecanismo de repasse, então o rótulo promete algo que não acontece.

Falta decidir entre:

- **Rótulo honesto**: trocar por um texto que descreva o estado real (ex.: "não enviada" ou "salva localmente");
- **Reenvio**: tentar enviar de novo quando um vizinho aparecer.

Onde mexe: `MensagemAdapter` (o rótulo) e `AgendaActivity.enviar()` (gravação).

## Fora do escopo por decisão

Estes itens **não** são tarefas — ficaram de fora de propósito (protótipo acadêmico, escopo pequeno e funcional):

- Criptografia — o texto trafega em claro.
- Relay/multi-hop — a mensagem só alcança quem está no alcance direto.
- Anexos — só texto.
- Canais protegidos e filtro de mensagens por canal — canal é apenas um rótulo local.
