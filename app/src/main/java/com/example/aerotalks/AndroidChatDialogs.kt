package com.example.aerotalks

import android.app.Activity
import android.graphics.Typeface
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * Dialogs de REDE e SOBRE (design §5).
 *
 * REDE le a [PeerTable] (lista que a Agenda passa) e a [IdentidadeStore]: meu
 * peerID/apelido editavel e, por peer, nome, status, o codigo de 6 digitos calculado
 * na hora e os botoes de travar/destravar. **Sem secao de canais**: `Canal` nao tem
 * coluna de origem e nada disso trafega.
 */
object AndroidChatDialogs {

    /**
     * Divergencia de apelido por peer (R3: o [Peer] nao cresce). Estado LOCAL do
     * dialog: guarda o apelido visto na ultima abertura e marca quem mudou.
     */
    private val apelidosVistos = HashMap<String, String>()

    /** Todos os dialogs abertos — cada `show()` entra aqui e sai no proprio dismiss. */
    private val abertos = mutableListOf<AlertDialog>()

    /**
     * Registra [dialog] (para o [fechar] alcancar tudo) e devolve o mesmo dialog.
     *
     * `onDismiss` chega **depois**: o Android posta a mensagem na main thread, e o positivo
     * de [verificar] chama [rede] enquanto o proprio [verificar] ainda esta saindo. Por isso
     * a remocao e **por instancia** (`===`), nunca `clear()` — um `clear()` no listener
     * apagaria da lista o dialog novo que a [rede] acabou de abrir, que voltaria a vazar
     * na rotacao.
     */
    /** Todo dialog do app passa por aqui para entrar na lista de rastreio. */
    fun mostrar(dialog: AlertDialog): AlertDialog {
        dialog.setOnDismissListener { abertos.removeAll { d -> d === dialog } }
        abertos.add(dialog)
        return dialog
    }

    fun rede(activity: Activity, peers: List<Peer>) {
        fechar() // reabrir troca pelo dialogo novo
        mostrar(
            AlertDialog.Builder(activity)
                .setTitle("Mesh local")
                .setView(ScrollView(activity).apply { addView(montarRaiz(activity, peers)) })
                .setPositiveButton("FECHAR", null)
                .show()
        )
    }

    /**
     * Fecha **todos** os dialogs abertos (rede, sobre, verificar, trocar apelido) e esvazia
     * a lista. Chame no `onDestroy` das Activities que abrem dialogs: a janela de um
     * Activity destruido vazava na rotacao (WindowLeaked).
     */
    fun fechar() {
        val copia = abertos.toList() // o dismiss reentra (onDismiss) e mexe na lista
        copia.forEach { it.dismiss() }
        abertos.clear()
    }

    private fun montarRaiz(activity: Activity, peers: List<Peer>): LinearLayout {
        // O par travado manda no estado: o PeerTable so reaplica isso no proximo ANNOUNCE.
        val lista = peers.map { it.copy(verificado = IdentidadeStore.verificado(it.id)) }
        val divergentes = HashMap<String, String>()

        // Poda: quem saiu do alcance nao pode continuar guardando apelido antigo aqui.
        apelidosVistos.keys.retainAll(lista.map { it.id })

        lista.forEach { peer ->
            val anterior = apelidosVistos[peer.id]
            if (anterior != null && anterior != peer.nickname) divergentes[peer.id] = anterior
            apelidosVistos[peer.id] = peer.nickname
        }

        val raiz = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
        }

        // ---- eu
        raiz.addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(linha(activity, "Minha id: ${IdentidadeStore.peerId}", 11f))
                addView(linha(activity, "eu: ${IdentidadeStore.apelido}", 11f))
                addView(
                    linha(
                        activity,
                        "${lista.count { it.conectado }} conectados de ${lista.size}",
                        11f
                    ).apply { setPadding(0, 8, 0, 8) }
                )
                addView(
                    Button(activity).apply {
                        text = "TROCAR APELIDO"
                        setOnClickListener { trocarApelido(activity) }
                    }
                )
            }
        )

        if (lista.isEmpty()) {
            raiz.addView(
                linha(activity, "Nenhum vizinho por perto ainda.", 13f)
                    .apply { setPadding(0, 16, 0, 0) }
            )
        }

        // ---- um bloco por peer
        lista.forEach { peer ->
            raiz.addView(blocoDoPeer(activity, peer, divergentes[peer.id]))
        }

        return raiz
    }

    private fun blocoDoPeer(activity: Activity, peer: Peer, apelidoAnterior: String?): LinearLayout {
        val bloco = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
        }

        bloco.addView(linha(activity, peer.nickname, 15f))
        bloco.addView(linha(activity, peer.status, 12f))

        if (apelidoAnterior != null) {
            bloco.addView(
                linha(
                    activity,
                    "apelido divergente: era \"$apelidoAnterior\", agora \"${peer.nickname}\"",
                    12f
                )
            )
        }

        // Codigo do par, calculado NA HORA a partir dos dois ids (nunca trafega).
        bloco.addView(
            linha(activity, "código: ${IdentidadeStore.codigoPara(peer.id)}", 15f)
                .apply { typeface = Typeface.MONOSPACE }
        )
        bloco.addView(
            linha(activity, "compare este código com o do outro aparelho, pessoalmente", 11f)
        )

        bloco.addView(
            Button(activity).apply {
                if (peer.verificado) {
                    text = "DESVERIFICAR"
                    setOnClickListener {
                        IdentidadeStore.destravar(peer.id)
                        Toast.makeText(activity, "Par desverificado.", Toast.LENGTH_SHORT).show()
                        rede(activity, PeerTable.peers())
                    }
                } else {
                    text = "VERIFICAR"
                    setOnClickListener { verificar(activity, peer) }
                }
            }
        )

        return bloco
    }

    /** Verificacao: os dois lados precisam ver o MESMO codigo e travar. */
    private fun verificar(activity: Activity, peer: Peer) {
        mostrar(
            AlertDialog.Builder(activity)
                .setTitle("Verificar ${peer.nickname}")
                .setMessage(
                    "Código: ${IdentidadeStore.codigoPara(peer.id)}\n\n" +
                        "Confira o MESMO número na tela do outro aparelho, pessoalmente. " +
                        "Se bater, trava o par aqui também — a outra ponta precisa fazer " +
                        "o mesmo."
                )
                .setNegativeButton("CANCELAR", null)
                .setPositiveButton("MARCO COMO VERIFICADO") { _, _ ->
                    IdentidadeStore.travar(peer.id, peer.nickname)
                    Toast.makeText(activity, "Par verificado.", Toast.LENGTH_SHORT).show()
                    rede(activity, PeerTable.peers())
                }
                .show()
        )
    }

    private fun trocarApelido(activity: Activity) {
        val campo = EditText(activity).apply {
            setText(IdentidadeStore.apelido)
            maxLines = 1
            hint = "Apelido (até 20 bytes no fio)"
        }
        mostrar(
            AlertDialog.Builder(activity)
                .setTitle("Meu apelido")
                .setView(campo)
                .setNegativeButton("CANCELAR", null)
                .setPositiveButton("SALVAR") { _, _ ->
                    IdentidadeStore.setApelido(campo.text.toString())
                    Toast.makeText(activity, "Apelido trocado.", Toast.LENGTH_SHORT).show()
                    rede(activity, PeerTable.peers())
                }
                .show()
        )
    }

    private fun linha(activity: Activity, texto: String, tamanho: Float): TextView =
        TextView(activity).apply {
            text = texto
            textSize = tamanho
        }

    fun about(activity: Activity) {
        val texto = TextView(activity).apply {
            setPadding(40, 24, 40, 8)
            textSize = 13f
            text = buildString {
                append("AeroTalks\n")
                append("Chat por mesh local: o aparelho conversa direto, por Bluetooth, ")
                append("com quem está por perto. Sem internet, sem servidor, sem conta.\n\n")
                append("Como funciona\n")
                append("· cada aparelho anuncia seu nome e seu id num serviço BLE próprio\n")
                append("· quem tem o id menor abre o servidor e o outro conecta — cada par, ")
                append("um único dono, sem conexão duplicada\n")
                append("· sem repasse: só quem está no alcance recebe, não existe salto\n")
                append("· o #canal e a nota são locais, cada aparelho guarda a sua lista\n")
                append("· a transcrição fica em SQLite no próprio aparelho\n")
                append("· o mesh liga e desliga junto com a tela da conversa\n\n")
                append("Verificação\n")
                append("· cada par tem um código de 6 dígitos, calculado no aparelho a partir ")
                append("dos dois ids — e o mesmo dos dois lados\n")
                append("· compare os códigos pessoalmente e trave o par\n\n")
                append("Honestidade do modelo\n")
                append("· o texto trafega em claro no BLE: não há criptografia\n")
                append("· o código é comparação presencial, não é autenticação — não protege ")
                append("contra quem retransmite as duas pontas com os ids verdadeiros\n")
                append("· o id é público por natureza; o segredo que o gera nunca sai do aparelho")
            }
        }

        mostrar(
            AlertDialog.Builder(activity)
                .setTitle("AeroTalks")
                .setView(texto)
                .setPositiveButton("FECHAR", null)
                .show()
        )
    }
}