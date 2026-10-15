package com.example.aerotalks

import android.content.Context
import android.content.Intent

/**
 * Tabela de peers em memoria (sem SQLite: a tabela do MeshStore e so de mensagens).
 * A UI le por [peers] e reage as duas acoes de broadcast.
 *
 * Thread-safety: chamadas do callback BLE chegam em thread de binder, a UI le da main.
 * Toda mutacao passa pelo lock; `lastSeen` e `conectado` sao rederivados na leitura.
 */
object PeerTable {

    /** A lista mudou (entrou/saiu/expirou/apelido). */
    const val ACTION_PEERS_CHANGED = "com.example.aerotalks.PEERS_CHANGED"

    /** Chegou mensagem: recarregar a conversa. Emitida por [mensagemRecebida]. */
    const val ACTION_MENSAGEM = "com.example.aerotalks.MENSAGEM"

    const val EXTRA_PEER_ID = "peer_id"
    const val EXTRA_TEXTO = "texto"

    /** Sem sinal alem de 180 s => conectado = false (tick de 15 s do controlador BLE). */
    const val EXPIRA_MS = 180_000L

    private class Linha(var peer: Peer, var lastSeen: Long)

    private val lock = Any()
    private val mapa = LinkedHashMap<String, Linha>()

    @Volatile
    private var appCtx: Context? = null

    /** Grava o contexto usado nos broadcasts. Idempotente; chame no `iniciar` do controlador. */
    fun iniciar(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /** Contexto ligado? Sem ele a tabela funciona, so nao avisa a UI. */
    fun pronto(): Boolean = appCtx != null

    // ------------------------------------------------------------- consultas

    /** Snapshot ja expirado, ordenada: conectados primeiro, depois id em ordem decrescente. */
    fun peers(): List<Peer> {
        expirar()
        synchronized(lock) {
            return mapa.values
                .map { it.peer }
                .sortedWith(compareByDescending<Peer> { it.conectado }.thenByDescending { it.id })
        }
    }

    /** Ultimo lastSeen (epoch millis), ou `null` se o peer nao esta na tabela. */
    fun lastSeenDe(id: String): Long? = synchronized(lock) { mapa[chave(id)]?.lastSeen }

    /** Existe na tabela? (o id do proprio aparelho nunca entra — o controlador filtra) */
    fun contem(id: String): Boolean = synchronized(lock) { mapa.containsKey(chave(id)) }

    // -------------------------------------------------------------- mutacoes

    /**
     * Insere/atualiza o peer. Apelido travado (verificado) ganha do prefs; id vazio
     * e ignorado (devolve um Peer solto, sem entrar na tabela).
     */
    fun registrar(peerId: String, apelido: String?, rssi: Int): Peer {
        val id = chave(peerId)
        if (id.isEmpty()) {
            // id invalido: nada entra na tabela
            return Peer(id = "", nickname = "", conectado = false, verificado = false, rssi = 0)
        }
        val travado = IdentidadeStore.apelidoTravado(id)
        val recebido = apelido?.trim().orEmpty()
        val nome = when {
            travado != null -> travado
            recebido.isNotEmpty() -> recebido
            else -> id.take(4)
        }
        val linha = Linha(
            Peer(
                id = id,
                nickname = nome,
                conectado = true,
                verificado = IdentidadeStore.verificado(id),
                rssi = rssi
            ),
            agora()
        )
        synchronized(lock) { mapa[id] = linha }
        avisar(ACTION_PEERS_CHANGED)
        return linha.peer
    }

    /** Da um "toque" no peer: renova lastSeen e revive se estava expirado. */
    fun tocar(id: String) {
        val k = chave(id)
        var reviveu = false
        synchronized(lock) {
            val linha = mapa[k] ?: return
            linha.lastSeen = agora()
            if (!linha.peer.conectado) {
                linha.peer = linha.peer.copy(conectado = true)
                reviveu = true
            }
        }
        if (reviveu) avisar(ACTION_PEERS_CHANGED)
    }

    /** Remove o peer. */
    fun remover(id: String) {
        val k = chave(id)
        val removido = synchronized(lock) { mapa.remove(k) != null }
        if (removido) avisar(ACTION_PEERS_CHANGED)
    }

    /** Zera a tabela (saida do mesh / teste). */
    fun limpar() {
        val tinha = synchronized(lock) {
            val naoVazia = mapa.isNotEmpty()
            mapa.clear()
            naoVazia
        }
        if (tinha) avisar(ACTION_PEERS_CHANGED)
    }

    /**
     * Poda: `now - lastSeen > [EXPIRA_MS]` => desconectado. Devolve quantos mudaram.
     * A UI so e avisada se algo mudou (o tick roda a cada 15 s).
     */
    fun expirar(): Int = expirar(agora())

    /** [expirar] com relogio injetado (o tick passa o seu; o teste passa um futuro). */
    fun expirar(agoraMs: Long): Int {
        var mudou = 0
        synchronized(lock) {
            for (linha in mapa.values) {
                if (linha.peer.conectado && agoraMs - linha.lastSeen > EXPIRA_MS) {
                    linha.peer = linha.peer.copy(conectado = false)
                    mudou++
                }
            }
        }
        if (mudou > 0) avisar(ACTION_PEERS_CHANGED)
        return mudou
    }

    /**
     * Um peer mandou MESSAGE: toca nele e avisa a UI. Chame DEPOIS de
     * `MeshStore.inserir` — aqui so entra o broadcast.
     */
    fun mensagemRecebida(peerId: String, texto: String?) {
        tocar(peerId)
        avisar(ACTION_MENSAGEM, peerId, texto)
    }

    // --------------------------------------------------------------- interno

    private fun chave(id: String) = id.trim().lowercase()

    private fun agora() = System.currentTimeMillis()

    private fun avisar(acao: String, peerId: String? = null, texto: String? = null) {
        val ctx = appCtx ?: return
        val i = Intent(acao).setPackage(ctx.packageName)
        if (peerId != null) i.putExtra(EXTRA_PEER_ID, peerId)
        if (texto != null) i.putExtra(EXTRA_TEXTO, texto)
        ctx.sendBroadcast(i)
    }
}
