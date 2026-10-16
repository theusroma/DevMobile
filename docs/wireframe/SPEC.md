# AeroTalks — SPEC de telas (Android / Kotlin)

Este documento descreve **como as telas devem ficar** e **como se comportam**. O app já existe: este spec é uma mudança sobre o código atual, não um app novo. Onde o spec diz "manter", não altere a lógica existente.

Referência visual: wireframes em `docs/wireframes/` (ver §9). O wireframe mostra estrutura e hierarquia, **não cores nem fontes finais**. Use o tema atual do projeto (`Theme.Aerotalks`).

---

## 1. Resumo da mudança

| # | Mudança | Onde |
|---|---|---|
| 1 | O apelido deixa de ser `AlertDialog` e vira **tela inteira** (`ApelidoActivity`) | `MainActivity.abrirAgenda()` |
| 2 | A conversa passa a ser um **chat**: balões + campo de mensagem fixo no rodapé | `AgendaActivity`, `activity_agenda.xml`, `item_evento.xml`, `MensagemAdapter` |
| 3 | `CadastroEventoActivity` é **removida** (o envio acontece na própria conversa) | manifest, Activity, layout |
| 4 | **Continuam como popup (`AlertDialog`)**: Mesh local (REDE), Verificar par, Sobre, Meu apelido (trocar) | `AndroidChatDialogs` — sem alteração |

---

## 2. Mapa de telas e navegação

Telas (Activities):

1. **Início** — `MainActivity` (launcher)
2. **Apelido** — `ApelidoActivity` (**nova**)
3. **Conversa** — `AgendaActivity`

Popups (`AlertDialog`, via `AndroidChatDialogs`):

- **Sobre** (aberto a partir de Início e de Conversa)
- **Mesh local (REDE)** (aberto a partir de Conversa)
- **Verificar par** (aberto a partir de REDE)
- **Meu apelido** (aberto a partir de REDE)
- **Escolher canal** e **Privado para** (**novos**, abertos a partir dos chips da Conversa — ver §5.4)

Fluxo:

```
Início ──COMEÇAR──► [portão BLE: suporte → permissão → Bluetooth ligado]
   │                          │
   │ SOBRE                    ▼
   ▼                    Apelido ──ENTRAR──► Conversa
 (popup Sobre)                                  │
                                                ├─ REDE ─► (popup Mesh local) ─┬─ VERIFICAR ─► (popup Verificar par)
                                                │                              └─ TROCAR APELIDO ─► (popup Meu apelido)
                                                ├─ INFO ─► (popup Sobre)
                                                ├─ chip canal ─► (popup Escolher canal)
                                                └─ chip privado ─► (popup Privado para)
```

---

## 3. Tela 1 — Início (`MainActivity`, `activity_main.xml`)

**Mudança:** nenhuma de layout. Só muda o destino do `COMEÇAR` (ver abaixo).

Conteúdo, centralizado na vertical:

- Título **"AeroTalks"** (grande, negrito, centralizado)
- Subtítulo **"O chat em mesh"** (cinza, centralizado)
- Botão **COMEÇAR** (`btnComecar`, largura total)
- Botão **SOBRE** (`btnSobre`, largura total)

Comportamento:

- **SOBRE** → abre o popup Sobre (`AndroidChatDialogs.about`). Manter.
- **COMEÇAR** → manter o portão atual (`comecar()`): sem BLE → toast e segue; sem permissão → pede; permissão negada → toast e segue; Bluetooth desligado → toast "Ligue o Bluetooth para o mesh funcionar." e fica na tela.
- Em **todos os caminhos que hoje chamam `abrirAgenda()`**, o destino agora é **`ApelidoActivity`** (e não mais o `AlertDialog`). Remover o `AlertDialog` de apelido e o campo `apelidoConfirmado` de `MainActivity` (ver §4 sobre o que fazer com ele).

---

## 4. Tela 2 — Apelido (`ApelidoActivity`, `activity_apelido.xml`) — NOVA

Substitui o `AlertDialog` "Como você quer ser chamado?". Mesmo conteúdo, agora como tela inteira.

Layout (conteúdo alinhado à esquerda, bloco centralizado na vertical, margens de 24dp):

- Título **"Como você quer ser chamado?"** (grande, negrito, pode quebrar em 2 linhas)
- Texto de apoio **"Esse nome vai para quem estiver por perto no mesh."** (cinza)
- Campo de texto, `hint = "Seu apelido no mesh"`, uma linha, pré-preenchido com `IdentidadeStore.apelido`
- Botão **ENTRAR** (largura total), abaixo do campo

Comportamento:

- **ENTRAR** → `IdentidadeStore.setApelido(campo.text.toString())`, depois `startActivity(AgendaActivity)` e `finish()` (para o botão voltar da Conversa não reabrir o apelido).
- Chamar `IdentidadeStore.iniciar(this)` no `onCreate`, como as outras telas.
- **Botão voltar do sistema:** volta para Início (comportamento padrão). Antes, o dialog era `setCancelable(false)`; agora não precisa trancar.
- Apelido vazio: manter o que `IdentidadeStore.setApelido` já faz (não alterar a regra de validação existente).
- Teclado: `windowSoftInputMode="adjustResize"` para o botão não ficar escondido.

**Quando a tela aparece:** hoje o dialog aparece em toda sessão (`apelidoConfirmado` começa `false` a cada abertura do app) e vem pré-preenchido. **Manter esse comportamento**: a tela aparece sempre que o usuário toca COMEÇAR e passa pelo portão. Como `MainActivity` não guarda mais o estado, o `apelidoConfirmado` deixa de existir — a tela simplesmente sempre aparece.

Registrar no `AndroidManifest.xml`: `<activity android:name=".ApelidoActivity" />`.

---

## 5. Tela 3 — Conversa (`AgendaActivity`, `activity_agenda.xml`)

### 5.1 Estrutura (de cima para baixo)

1. **Cabeçalho**
   - Esquerda: título `tvHeaderCanal` ("#geral · anônimo", formato atual `#canal · apelido`) e abaixo `tvStatus` ("BLE · CONECTADOS 1 · MTU 23", menor e cinza).
   - Direita: botões **REDE** (`btnRede`) e **INFO** (`btnInfo`), lado a lado.
   - Linha divisória fina abaixo do cabeçalho.
2. **Lista de mensagens** (`rvEventos`, ocupa todo o espaço restante, rola)
3. **Linha de chips** (acima do campo): chip de canal e chip de destinatário privado
4. **Barra de envio** (fixa no rodapé): campo de texto + botão **ENVIAR**

**Remover** da tela: botão `btnNovoEvento` ("NOVA MENSAGEM") e botão `btnVoltar` ("VOLTAR", o botão voltar do sistema já cumpre esse papel).

O rodapé (chips + barra de envio) deve **subir junto com o teclado** (`android:windowSoftInputMode="adjustResize"` para `AgendaActivity` no manifest). A lista não deve ficar escondida atrás do teclado.

### 5.2 Mensagens (balões)

Cada item (`item_evento.xml`, adaptado) é um **balão**:

- **Minhas mensagens** (`msg.minha == true`): alinhadas à **direita**, fundo cinza preenchido, **sem** linha de autor.
- **Mensagens dos outros**: alinhadas à **esquerda**, fundo claro com borda, **com** o nome do autor em negrito pequeno no topo.
- Largura máxima do balão: ~78% da largura da lista.
- Dentro do balão, de cima para baixo: (autor, só nos outros) → **conteúdo** → **linha de metadados** pequena e cinza.
- **Linha de metadados** (mesma regra que `MensagemAdapter` já usa, com a hora na frente):
  - `"{hora} · #{canal}"`, ou `"{hora} · privado → {privadoPara}"` se for privada
  - `+ " · menção"` se `msg.mencao`
  - `+ " · aguardando repasse"` se `!msg.entregue`
  - Exemplos do wireframe: `10:42 · #geral`, `10:43 · aguardando repasse`, `10:44 · privado`, `10:45 · #geral · menção`.

Mudanças em `MensagemAdapter`:

- Hoje o texto é `"${hora}  ${autor}: ${conteudo}"`. Passa a ser: autor e conteúdo em `TextView`s separados, hora na linha de metadados.
- Usar dois tipos de view (`getItemViewType` por `msg.minha`), ou um único layout com `gravity`/`layout_gravity` ajustados no bind. Escolha a opção mais simples.
- Manter os ids `tvNomeEvento` (conteúdo) e `tvDataLocal` (metadados) para reduzir o diff; adicionar `tvAutor`.

**Estado vazio:** se a lista estiver vazia, mostrar texto centralizado "Nenhuma mensagem ainda." (novo, simples; some quando chega a primeira).

**Filtro por canal:** **não** filtrar a lista por canal. `store.listar()` continua trazendo tudo, como hoje. O canal é só um rótulo local (ver §5.4).

### 5.3 Barra de envio

- Campo `etMensagem`: `hint = "Escreva uma mensagem…"`, uma linha que pode crescer até ~4 linhas, com `InputFilter.LengthFilter(BleMesh.MAX_TEXT)` (mesmo filtro que `CadastroEventoActivity` usa hoje em `etNome`).
- Botão **ENVIAR** (`btnEnviar`) à direita do campo.
- Ação "enviar" do teclado (IME) também envia.

**Lógica de envio** — mover para `AgendaActivity`, **a mesma que hoje está em `CadastroEventoActivity.btnSalvar`**, com estas diferenças:

1. `texto = etMensagem.text.trim()`. Se vazio: não faz nada (sem toast).
2. `canal` = canal escolhido no chip (já em minúsculas, sem `#`; padrão `"geral"`).
3. `privadoPara` = id (16 hex) escolhido no chip, ou `null`. Não precisa mais validar com regex: o id vem da lista de vizinhos, não de digitação.
4. Checagem dura de bytes UTF-8: `bytes > BleMesh.MAX_TEXT` → `longaDemais`, não envia, **não trunca**.
5. `saiu = !longaDemais && BleMesh.enviar(texto, privadoPara)`.
6. Gravar **sempre** em `MeshStore.inserir(Mensagem(autor = IdentidadeStore.apelido, conteudo = texto, canal, privadoPara, hora = agora(), minha = true, entregue = saiu))`.
7. Toast, mesmos textos de hoje:
   - `longaDemais` → "Mensagem longa demais (máx {MAX_TEXT} bytes) — salva apenas neste aparelho"
   - `saiu` → "Mensagem enviada"
   - `privadoPara != null` → "Sem rota para esse vizinho — salva apenas neste aparelho"
   - senão → "Nenhum vizinho com sinal agora — salva apenas neste aparelho"
8. **Novo, por estar na mesma tela:** limpar `etMensagem`, chamar `recarregar(rolar = true)`. **Não** chamar `finish()`.
9. Depois de enviar, o chip de privado volta para "Privado: ninguém" (evita mandar a próxima mensagem em privado sem querer). O canal **permanece**.

**Nota/anexo:** o campo "nota" da tela antiga **não existe mais** no chat. Remover `etDescricao` e a lógica `"$texto\n[$nota]"`. O texto salvo é só o `texto`.

**Mesh:** não chamar `BleMesh.adquirir/liberar` para o envio. A `AgendaActivity` já faz isso em `onStart/onStop`, e agora o envio acontece nela mesma, então o mesh já está ligado.

### 5.4 Chips de canal e privado (comportamento novo)

Dois chips (botões com borda arredondada e `▾`), lado a lado, acima da barra de envio.

**Chip de canal** — texto `"#{canalAtual} ▾"` (ex.: `#geral ▾`).

- Toque → popup (`AlertDialog`) **"Canal"** com um campo de texto pré-preenchido com o canal atual e botões CANCELAR / OK.
- Ao confirmar: normalizar como `CadastroEventoActivity` faz hoje (`trim`, `removePrefix("#")`, `lowercase`, vazio → `"geral"`), atualizar `canalAtual`, atualizar o chip e chamar `atualizarCabecalho()` (o título `#canal · apelido` muda).
- `Canal.kt` e a lógica de canais "dentro/membros/protegido" **não entram**: canal continua sendo só um rótulo local, não trafega no fio.

**Chip de privado** — texto `"Privado: ninguém ▾"` ou `"Privado: {apelido do vizinho} ▾"`.

- Toque → popup (`AlertDialog`) **"Privado para"** com uma lista de escolha única:
  - primeira opção: **"Ninguém (mensagem aberta)"**
  - uma opção por vizinho de `PeerTable.peers()`: `"{nickname} · {status}"` (reusar `Peer.status`)
  - se não houver vizinhos: além da opção "Ninguém", mostrar o texto "Nenhum vizinho por perto ainda." (mesma frase do popup REDE)
- Escolher um vizinho define `privadoPara = peer.id`.
- Vizinhos desconectados **aparecem e podem ser escolhidos**; o envio cai no caminho "Sem rota para esse vizinho — salva apenas neste aparelho" que já existe.
- Todos os popups novos devem passar por `AndroidChatDialogs.mostrar(...)`, para serem fechados em `AndroidChatDialogs.fechar()` no `onDestroy` (evita `WindowLeaked` na rotação).

### 5.5 Botões do cabeçalho (manter)

- **REDE** → `AndroidChatDialogs.rede(this, PeerTable.peers())`
- **INFO** → `AndroidChatDialogs.about(this)`

### 5.6 Ciclo de vida (manter)

`onStart`: `IdentidadeStore.iniciar`, `BleMesh.adquirir`, registrar o receiver de `ACTION_PEERS_CHANGED` e `ACTION_MENSAGEM`. `onStop`: desregistrar o receiver **antes** de `BleMesh.liberar()`. `onResume`: `recarregar()`. Não mexer nisso.

---

## 6. Popups (manter como estão)

Não alterar `AndroidChatDialogs`. Para referência do que existe:

| Popup | Título | Conteúdo | Botões |
|---|---|---|---|
| **Sobre** | "AeroTalks" | descrição, "Como funciona", "Verificação", "Honestidade do modelo" | FECHAR |
| **Mesh local** | "Mesh local" | minha id, meu apelido, "N conectados de M", TROCAR APELIDO, um bloco por vizinho (nome, status, "código: 000 000", instrução, VERIFICAR/DESVERIFICAR), "Nenhum vizinho por perto ainda." se vazio | FECHAR |
| **Verificar par** | "Verificar {nome}" | código de 6 dígitos + instrução de comparar pessoalmente | CANCELAR / MARCO COMO VERIFICADO |
| **Meu apelido** | "Meu apelido" | campo, `hint = "Apelido (até 20 bytes no fio)"` | CANCELAR / SALVAR |

---

## 7. Remoções

- `CadastroEventoActivity.kt`
- `res/layout/activity_cadastro_evento.xml`
- `<activity android:name=".CadastroEventoActivity" />` no `AndroidManifest.xml`
- Em `AgendaActivity`: `btnNovoEvento`, `btnVoltar` e o `Intent` para `CadastroEventoActivity`
- Em `MainActivity`: o `AlertDialog` de apelido e a variável `apelidoConfirmado`

Antes de remover, confirmar com uma busca no projeto que nada mais referencia esses símbolos.

---

## 8. Fora de escopo

- Criptografia, relay/multi-hop e qualquer mudança no protocolo BLE (`BleMesh`, `BleCodec`, `PeerTable`).
- Lista de canais, canais protegidos e filtro de mensagens por canal.
- Anexos e nota por mensagem.
- Tema escuro/claro, ícones e identidade visual (o wireframe é em tons de cinza).
- Reescrever o layout em Compose: manter Views/XML como hoje.

---

## 9. Imagens de referência

Colocar em `docs/wireframes/`:

- `01-inicio.png` — tela de Início
- `02-apelido.png` — tela de Apelido
- `03-conversa-chat.png` — tela de Conversa em formato de chat (**a versão válida**, com balões, chips e campo de envio)

As telas antigas "Nova mensagem" e a Conversa com botão "NOVA MENSAGEM" estão **obsoletas** e não devem ser usadas como referência.

---

## 10. Critérios de aceite

1. Tocar em COMEÇAR (com BLE e permissões OK) abre a tela **Apelido** inteira, pré-preenchida com o apelido atual; ENTRAR leva à Conversa e o botão voltar da Conversa **não** reabre o Apelido.
2. SOBRE em Início e INFO na Conversa abrem o mesmo popup Sobre.
3. A Conversa **não tem** botões "NOVA MENSAGEM" nem "VOLTAR"; tem campo de mensagem e ENVIAR fixos no rodapé, que sobem com o teclado.
4. Minhas mensagens aparecem à direita; as dos outros, à esquerda e com o nome do autor.
5. Enviar uma mensagem: aparece na lista na hora, o campo é limpo, a tela **não fecha**, e o toast correto aparece (enviada / sem vizinho / sem rota / longa demais).
6. Mensagem acima do limite de bytes **não é enviada nem truncada**, mas é guardada localmente.
7. O chip de canal muda o `#canal` do cabeçalho e o rótulo das próximas mensagens; o chip de privado lista os vizinhos e, após o envio, volta para "ninguém".
8. REDE continua abrindo o popup Mesh local, com TROCAR APELIDO e VERIFICAR funcionando como antes.
9. Girar a tela com qualquer popup aberto não gera `WindowLeaked`.
10. O projeto compila sem referências a `CadastroEventoActivity`.
