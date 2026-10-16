# AeroTalks

Chat sobre **mesh BLE** para Android: os aparelhos conversam direto por Bluetooth com quem está por perto — **sem internet, sem servidor, sem conta**. Protótipo (v1); o texto trafega em claro.

## Como funciona

- Fluxo: **Início** (portão de BLE/permissões) → **Apelido** → **Conversa** (o chat, `AgendaActivity`).
- Malha local: advertising/scanning BLE + GATT (quem tem o peerID hex maior inicia a conexão). Sem relay/multi-hop e sem criptografia.
- Envio: só o **texto cru** cruza o fio (até 140 bytes UTF-8); canal e privado são rótulos locais. Toda mensagem fica gravada no aparelho (SQLite) mesmo sem rota.

## Build e testes

```bash
./gradlew :app:assembleDebug        # APK em app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # 34 testes JVM (codec/mesh)
```

Requisitos: Android Studio + SDK 37 (compile/target); Android 7.0+ (minSdk 24).

## Documentação

- [AGENTS.md](AGENTS.md) — guia do projeto para agentes de código (mapa, convenções, armadilhas).
- [TODO.md](TODO.md) — próximas tarefas pendentes.
- [docs/wireframe/SPEC.md](docs/wireframe/SPEC.md) — spec das telas (Início/Apelido/Conversa).
