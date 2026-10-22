# AeroTalks

Um app de chat para Android que funciona **sem internet**. As mensagens vão de um celular para outro direto por Bluetooth, sem servidor e sem criar conta.

> Este é um protótipo acadêmico: as mensagens não são criptografadas.

## Como usar

1. **Início:** toque em COMEÇAR. O app confere se o Bluetooth está disponível e pede as permissões necessárias.
2. **Apelido:** escolha o nome que os outros vão ver.
3. **Conversa:** escreva e envie. Quem estiver por perto com o app aberto recebe a mensagem.

## Como funciona

- **Encontrar quem está perto:** cada celular fica avisando "estou aqui" por Bluetooth e, ao mesmo tempo, procurando outros celulares com o app.
- **Conectar:** quando dois celulares se encontram, eles se conectam sozinhos e já podem trocar mensagens.
- **Enviar:** só o texto da mensagem é transmitido, com no máximo 140 bytes (cerca de 140 caracteres; acentos e emojis ocupam mais espaço).
- **Guardar:** toda mensagem fica salva no próprio celular, mesmo quando não tem ninguém por perto para receber.

## Estrutura do projeto

```
DevMobile/
├── app/src/main/java/com/example/aerotalks/   # todo o código do app
├── app/src/main/res/                          # telas (layouts), cores, ícones e temas
├── app/src/test/                              # testes automáticos
├── docs/wireframe/                            # desenhos das telas
├── README.md                                  # este arquivo
├── AGENTS.md                                  # detalhes técnicos do código
└── TODO.md                                    # o que ainda falta fazer
```

### Arquivos mais importantes (em `app/src/main/java/com/example/aerotalks/`)

**Telas**

- `MainActivity.kt`: tela de Início. Confere Bluetooth e permissões.
- `ApelidoActivity.kt`: tela onde você escolhe o apelido.
- `AgendaActivity.kt`: a tela de Conversa, onde você vê e envia as mensagens.

**Bluetooth**

- `BleMesh.kt`: o coração do app. Procura outros celulares, conecta e envia/recebe mensagens.
- `BleCodec.kt`: transforma a mensagem no formato que viaja pelo Bluetooth e desfaz isso na chegada.
- `BlePerms.kt`: cuida das permissões de Bluetooth.
- `PeerTable.kt` e `Peer.kt`: lista dos vizinhos encontrados por perto.

**Dados guardados no celular**

- `IdentidadeStore.kt`: guarda o seu apelido e a sua identidade no aparelho.
- `MeshStore.kt`: banco de dados com o histórico de mensagens.
- `Mensagem.kt`: define o que é uma mensagem (autor, texto, hora etc.).

**Visual**

- `MensagemAdapter.kt`: desenha os balões de conversa na tela.
- `AndroidChatDialogs.kt`: reúne todos os pop-ups do app.

## Como rodar

Você precisa do Android Studio e de um celular com Android 7.0 ou mais novo. Para testar a conversa, use pelo menos dois aparelhos, porque o emulador não tem Bluetooth.

```bash
./gradlew :app:assembleDebug   # gera o APK em app/build/outputs/apk/debug/
```

## Para saber mais

- [docs/wireframe/SPEC.md](docs/wireframe/SPEC.md): desenho das telas.
- [AGENTS.md](AGENTS.md): detalhes técnicos do código.
