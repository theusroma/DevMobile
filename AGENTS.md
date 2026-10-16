# AGENTS.md — guia do projeto AeroTalks

Guia permanente para agentes de código que trabalharem neste repositório. Descreve o que o projeto é, como está montado e as convenções que não devem ser quebradas. Fatos verificados no código atual (fonte de verdade). Em caso de divergência com qualquer documento antigo (recon, wireframe), **o código vence**.

## 1. Visão geral

- App Android de **chat sobre mesh BLE**: os aparelhos conversam direto por Bluetooth com quem está por perto, **sem internet, sem servidor, sem conta**. O texto trafega em claro.
- É um **protótipo** (v1), não um produto. Pacote: `com.example.aerotalks`.
- Projeto Gradle de módulo único: raiz `aerotalks`, módulo `:app`.
- Não há relay/multi-hop, criptografia nem anexos (fora de escopo no v1).
- Toda a documentação e os comentários são em **PT-BR**.

## 2. Stack / baseline

- Linguagem: **Kotlin** (via suporte embutido do AGP — não há plugin Kotlin declarado nem versão fixada no catálogo).
- UI: **Views/XML**. **Sem Compose e sem ViewBinding**; o código usa `findViewById`. Widgets são os padrões do tema.
- Versões reais (lidas dos arquivos): AGP `9.4.1` (`gradle/libs.versions.toml`); Gradle wrapper `9.6.0` (`gradle/wrapper/gradle-wrapper.properties`); `compileSdk = 37`, `targetSdk = 37`, `minSdk = 24` (`app/build.gradle.kts`); Java source/target `11`.
- Deps: core-ktx `1.10.1`, appcompat `1.6.1`, activity-ktx `1.8.0`, constraintlayout `2.1.4`, material `1.10.0`; `androidx.recyclerview:recyclerview:1.3.2` está **hardcoded** no `app/build.gradle.kts` (não no catálogo).
- `gradle.properties`: **configuration-cache ligado** (`org.gradle.configuration-cache=true`), `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8`.
- Comandos: build `./gradlew :app:assembleDebug` (APK em `app/build/outputs/apk/debug/app-debug.apk`); testes JVM `./gradlew :app:testDebugUnitTest` (**34 testes**, todos em `app/src/test/java/com/example/aerotalks/ExampleUnitTest.kt`).
- Os testes existentes são **puramente JVM** (round-trip do codec, código simétrico, PeerTable). Há um teste de instrumentação padrão, sem cobertura real.

## 3. Mapa do código

`app/src/main/java/com/example/aerotalks/` — Activities (o fluxo são 3 telas):
- `MainActivity.kt` — **Início** (launcher). Portão: BLE, permissões e Bluetooth ligado; no fim sempre encaminha para `ApelidoActivity`.
- `ApelidoActivity.kt` — **Apelido**. Tela inteira que pré-preenche o apelido atual; ENTRAR salva (`IdentidadeStore.setApelido`), abre `AgendaActivity` e chama `finish()`.
- `AgendaActivity.kt` — **Conversa** (o chat). Balões, chips de canal/privado, barra de envio no rodapé, REDE/INFO no cabeçalho. É aqui que o envio acontece.

Camada BLE/mesh:
- `BleMesh.kt` — controlador da malha (advertiser + scanner + GATT client + GATT server); objeto ligado ao ciclo de vida da tela, **não é Service**. Envio, recepção, dedupe, backoff, MTU e poda; mantém `PeerTable`/`MeshStore`.
- `BleCodec.kt` — codec do envelope v1. Kotlin puro (sem Android), por isso é testável no host; é a fonte dos limites de wire (`MAX_TEXT`, `NICK_MAX`, `ID_LEN`, `MAX_FRAME`).
- `BlePerms.kt` — portão de permissões e de liga/desliga do Bluetooth (regras por API).
- `PeerTable.kt` — tabela de peers **em memória** (+ broadcast de mudança e de mensagem recebida). Não usa SQLite.
- `Peer.kt` — data class do vizinho (`id`, `nickname`, `conectado`, `verificado`, `rssi`) e o getter `status`.

Persistência:
- `IdentidadeStore.kt` — identidade local em `SharedPreferences` (secret de 32 B → peerID, apelido, par verificado). O secret nunca sai do aparelho e nunca trafega.
- `MeshStore.kt` — `SQLiteOpenHelper` (`mesh.db`), tabela `mensagens` (transcrição local); `listar()` devolve tudo em ordem de id.

UI do chat e utilidades:
- `MensagemAdapter.kt` — `RecyclerView.Adapter` dos balões; um só layout de item, o balão vira enviado/recebido no bind.
- `AndroidChatDialogs.kt` — `object` com **todos** os popups (`AlertDialog`): Sobre, Mesh local (REDE), Verificar par, Meu apelido, Canal, Privado para.
- `Insets.kt` — extensão `View.aplicarInsetsDoSistema()` (edge-to-edge/IME).
- `Canal.kt` — data class `Canal` (nome/dentro/membros/protegido). **Não é usada no fluxo do chat** (canal é só rótulo local); existe no código mas sem referências.

Modelo:
- `Mensagem.kt` — data class da mensagem (`autor`, `conteudo`, `canal`, `privadoPara`, `hora`, `minha`, `entregue`, `mencao`) e o getter `ehPrivada`.

## 4. Fluxo do app

`Início` (MainActivity) → `Apelido` (ApelidoActivity) → `Conversa` (AgendaActivity).

1. **COMEÇAR** roda o portão: sem BLE no hardware → avisa e segue; permissão negada → avisa e segue; Bluetooth desligado → pede para ligar. Todos os caminhos terminam em `ApelidoActivity`. O app funciona **read-only** quando o mesh não sobe.
2. **Apelido** aparece sempre (não há mais estado `apelidoConfirmado`). ENTRAR salva e vai para a Conversa; o voltar da Conversa retorna ao Início (o Apelido dá `finish()`).
3. **Conversa é a `AgendaActivity`** — sempre que falarmos "conversa" ou "chat", é essa tela.

Envio de mensagem (`AgendaActivity.enviar()`):
- Só o **texto cru** cruza o fio. **Canal e privado são rótulos LOCAIS** — não vão no pacote.
- Checagem dura de **bytes UTF-8**: `bytes > BleMesh.MAX_TEXT` (140) → não envia e **não trunca**.
- Grava **sempre** no `MeshStore` (mesmo sem rota/sem sinal) e mostra um toast: "Mensagem enviada" / "Sem rota para esse vizinho — salva apenas neste aparelho" / "Nenhum vizinho com sinal agora — salva apenas neste aparelho" / "Mensagem longa demais … — salva apenas neste aparelho".
- Depois de enviar: limpa o campo, recarrega a lista (rolando até o fim) e o chip de privado volta para "Privado: ninguém" (o canal permanece).
- Chips: o de canal abre o popup "Canal" (normaliza `trim`, sem `#`, minúsculo, vazio → `geral`); o de privado abre a lista de vizinhos de `PeerTable.peers()`.

Ciclo de vida da Agenda: `onStart` → `IdentidadeStore.iniciar` + `BleMesh.adquirir` + registrar o receiver; `onStop` → desregistrar o receiver **antes** de `BleMesh.liberar`; `onResume` → `recarregar()`; `onDestroy` → `AndroidChatDialogs.fechar()`.

## 5. BLE / mesh

Envelope (`BleCodec`): cabeçalho fixo de **30 bytes**, multi-byte em big-endian. `version=0x01`, `type` (1 ANNOUNCE / 2 MESSAGE / 3 LEAVE), `ttl=0x01` (sem relay), `timestamp` (8 B), `flags`, `payloadLen` (2 B, tem de bater com `size-30`), `senderID` (8 B), `recipientID` (8 B). Limites: `MAX_FRAME=200`, `MAX_TEXT=140` (payload de MESSAGE), `ID_LEN=8`, `NICK_MAX=20` (bytes). `decode` **nunca lança**: devolve `null` para qualquer frame fora do contrato.

Advertising/discovery: serviço `6d9f0001-…`, característica `6d9f0002-…` (WRITE + WRITE_NO_RESPONSE + NOTIFY) e CCCD. O advertising e o filtro de scan usam o service data de **16 bits `0xFD6F`** (nunca UUID 128-bit nesses campos — estoura os 31 B do advertising). O peerID vai nos 8 bytes do service data; `onAnunciado` só aceita exatamente 8 B.

Papéis e iniciador: quem tem o **peerID hex MAIOR** é cliente (conecta e escreve); o menor abre o GATT server e anuncia por notify. O gate é determinístico pela string hex do peerID (evita os dois lados conectarem um no outro).

MTU: pede-se `517`; abaixo de `173` o link fica `INCOMPATIVEL`. Overhead ATT de 3 B (`30 + len <= mtu - 3`). O MTU é mostrado no cabeçalho da Conversa (`"BLE · CONECTADOS n · MTU m"`, junto de `#{canal} · {apelido}`).

Permissões (`BlePerms` + manifest): **API ≤ 30** → `ACCESS_FINE_LOCATION` em runtime (exigida pelo scan); `BLUETOOTH`/`BLUETOOTH_ADMIN` são de instalação (`maxSdkVersion=30`). **API 31+** → `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` + `BLUETOOTH_ADVERTISE` pedidas juntas, com `usesPermissionFlags="neverForLocation"`; localização **não** é pedida. Sem `BLUETOOTH_ADVERTISE` o `startAdvertising` lança `SecurityException` no 31+ e nada é descoberto.

## 6. Convenções que NÃO devem ser quebradas

- **PT-BR** em comentários, docstrings e mensagens de commit.
- Comentar **só onde o código existente já comenta**. Não encher de comentário o que é óbvio.
- **Popups sempre via `AndroidChatDialogs`**, registrando em `mostrar(...)` e fechando em `fechar()` no `onDestroy`. Isso evita `WindowLeaked` na rotação. Não abrir `AlertDialog` avulso fora desse objeto.
- **Insets de tela via `Insets.kt`** (`aplicarInsetsDoSistema()` no root, no `onCreate`, após `setContentView`). Com `targetSdk 35+` o edge-to-edge é imposto e `adjustResize` sozinho não redimensiona mais: não voltar a depender só do manifest para o teclado.
- **Cores via tema** `Theme.Aerotalks` (Material3 `DayNight`). Nada de cor hardcoded; use atributos do tema (`?attr/…`, `?android:attr/textColorSecondary`).
- **Strings hardcoded** nos layouts/código é a convenção do repo (não há uso de `strings.xml` além de `app_name`). Não introduzir recursos de string sem necessidade.
- **`findViewById`** para achar views. Não ligar ViewBinding nem Compose sem motivo forte.
- **Sem dependências novas sem motivo** (nem CardView, nem bibliotecas de UI).
- **Item pendente/não funcional** → registrar em `TODO.md` e marcar `// TODO:` (ou `<!-- TODO: -->`) no ponto do código, terminando com "ver TODO.md".
- Não mexer no protocolo BLE (`BleMesh`, `BleCodec`, `PeerTable`) sem necessidade — há 34 testes JVM que cobrem o codec e devem continuar verdes.

## 7. Emulador / teste manual

Não existe auto-teste de UI. A verificação de tela é manual, via `adb` + `uiautomator`.

- AVD: `Medium_Phone_API_37.0` (API 37).
- SDK/adb: `local.properties` aponta `sdk.dir=/home/ladinx/Android/Sdk`; adb em `/home/ladinx/Android/Sdk/platform-tools/adb`.
- Boot headless: `emulator -avd Medium_Phone_API_37.0 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect`.
- Instalar: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- **Gotcha**: após `pm clear com.example.aerotalks`, as permissões runtime voltam a ser pedidas. Reconceda via `pm grant` (`BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`) para não travar no diálogo do sistema.
- Dirija a UI por `adb shell uiautomator dump` + `input tap <x> <y>` usando os **bounds** do dump (por id, ex. `btnComecar`, `etApelido`, `chipCanal`, `btnEnviar`). `input text` não digita espaços: use `%s`.
- Roteiro de smoke: Início → COMEÇAR → Apelido → ENTRAR → Conversa → digitar e ENVIAR → conferir balão/`tvDataLocal` → abrir REDE e girar a tela (checar ausência de `WindowLeaked` no logcat) → BACK volta ao Início.

## 8. Armadilhas conhecidas

- **Rotação fecha os popups por design** — o `AndroidChatDialogs.fechar()` roda no `onDestroy` e os dialogs não sobrevivem à mudança de configuração. É esperado e **não gera `WindowLeaked`** (é justamente o que a convenção evita).
- **Estado de canal/privado da tela não persiste em rotação** — `canalAtual` e `privadoPara` são campos de instância, sem `onSaveInstanceState`. Comportamento esperado hoje.
- **Teclado/rodapé dependem dos insets**: sem `aplicarInsetsDoSistema()`, o rodapé (chips + barra de envio) fica atrás do IME. Foi bug real, corrigido; não remova a chamada.
- **Sem BLE no emulador**, o app cai no caminho "sem vizinho": o portão avisa e segue, o header mostra `CONECTADOS 0` e `MTU 0`, e o envio grava local com toast de "salva apenas neste aparelho".
- Sem link PRONTO, `BleMesh.mtu()` devolve `0` (não os `23` do exemplo no layout).
- `Canal.kt` existe mas não entra no fluxo do chat (canal é rótulo local, não trafega).

## 9. Git

- Commits **curtos, em PT-BR**, um por mudança coesa (ver `git log --oneline`).
- Não commitar `local.properties`, `build/`, nem artefatos do `.idea` (já no `.gitignore`).

## 10. Referências

- `docs/wireframe/SPEC.md` — spec das telas do refactor de chat (Início/Apelido/Conversa, chips, envio). Obs.: o texto do SPEC cita `docs/wireframes/` (plural), mas o diretório real é `docs/wireframe/` (singular).
- `docs/wireframe/01-inicio.png`, `02-apelido.png`, `03-conversa-chat.png` — wireframes: mostram **estrutura e hierarquia**, NÃO cores nem fontes finais.
- `.slim/deepwork/chat-wireframe/recon-code.md` — mapa de recon **anterior ao refactor**; útil como histórico, mas valide tudo contra o código atual.
- `TODO.md` — próximas tarefas pendentes (cada item tem marca `TODO` no código).
