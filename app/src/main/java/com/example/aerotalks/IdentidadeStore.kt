package com.example.aerotalks

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Identidade local: secret de 32 B -> peerID, apelido editavel e o par travado
 * (verificado) por prefs. O secret NUNCA trafega e nunca sai do aparelho.
 */
object IdentidadeStore {

    private const val PREFS = "aerotalks_identidade"
    private const val K_SECRET = "secret_hex"
    private const val K_APELIDO = "apelido"

    private const val SEGREDO_BYTES = 32
    private const val MOD_CODIGO = 1_000_000L

    @Volatile
    private var prefs: SharedPreferences? = null

    private var secretHex: String? = null
    private var peerIdCache: String? = null

    /**
     * Liga o store. Idempotente e lazy: pode ser chamado em qualquer Activity.
     * O secret so e gerado no primeiro acesso a [peerId]/[apelido].
     */
    @Synchronized
    fun iniciar(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Prefs, ou `null` se [iniciar] ainda nao rodou (leitor-safe: PeerTable usa). */
    private fun pOrNull(): SharedPreferences? = prefs

    private fun p(): SharedPreferences =
        prefs ?: throw IllegalStateException("IdentidadeStore.iniciar(ctx) nao foi chamado")

    // ------------------------------------------------------------ identidade

    /** Os 8 bytes do secret = o que vai no senderID do envelope. */
    private fun secretBruto(): ByteArray {
        secretHex?.let {
            BleCodec.deHex(it)?.let { b -> if (b.size == SEGREDO_BYTES) return b }
        }
        val novo = ByteArray(SEGREDO_BYTES)
        SecureRandom().nextBytes(novo)
        val hex = BleCodec.hex(novo)
        secretHex = hex
        p().edit().putString(K_SECRET, hex).apply()
        return novo
    }

    /**
     * peerID = hex minusculo dos 8 primeiros bytes do SHA-256(secret) => 16 chars.
     * E exatamente o senderID do envelope.
     */
    val peerId: String
        get() {
            peerIdCache?.let { return it }
            val d = MessageDigest.getInstance("SHA-256").digest(secretBruto())
            val id = BleCodec.hex(d, 0, BleCodec.ID_LEN)
            peerIdCache = id
            return id
        }

    /** Os [BleCodec.ID_LEN] bytes crus do meu peerID (prontos pro envelope). */
    fun meuIdBruto(): ByteArray = BleCodec.deHexId(peerId) ?: ByteArray(BleCodec.ID_LEN)

    /** Apelido corrente; default `"voador-" + peerId.take(4)`, persistido no 1o uso. */
    val apelido: String
        get() {
            val p = p()
            val salvo = p.getString(K_APELIDO, null)?.trim()
            if (!salvo.isNullOrEmpty()) return salvo
            val padrao = "voador-" + peerId.take(4)
            p.edit().putString(K_APELIDO, padrao).apply()
            return padrao
        }

    /**
     * Grava o apelido. Guarda o texto inteiro; o corte em [BleCodec.NICK_MAX] bytes
     * acontece no fio, em [BleCodec.tlvApelido]. Vazio volta para o default.
     */
    fun setApelido(s: String) {
        val limpo = s.trim()
        val valor = if (limpo.isEmpty()) "voador-" + peerId.take(4) else limpo
        p().edit().putString(K_APELIDO, valor).apply()
    }

    // ----------------------------------------------------------- verificacao

    /**
     * Codigo de 6 digitos do par (safety number local, NUNCA trafega).
     * Simetrico: os dois lados calculam o mesmo numero em cima dos mesmos dois IDs.
     * Verdadeiro contra MITM que se apresenta com ID proprio — nao e autenticacao.
     */
    fun codigoPara(peerIdRemoto: String): String = codigoSimetrico(peerId, peerIdRemoto)

    /** Corpo puro e simetrico de [codigoPara] (testavel sem Context). */
    fun codigoSimetrico(meuId: String, idRemoto: String): String {
        val a = meuId.trim().lowercase()
        val b = idRemoto.trim().lowercase()
        if (a.isEmpty() || b.isEmpty()) return "------" // nao e um codigo valido
        val par = if (a <= b) listOf(a, b) else listOf(b, a) // (menor, maior)
        val d = MessageDigest.getInstance("SHA-256").digest((par[0] + par[1]).toByteArray(Charsets.UTF_8))
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (d[i].toLong() and 0xFF) // uint32 BE
        return (v % MOD_CODIGO).toString().padStart(6, '0')
    }

    // ------------------------------------------------------------ par travado

    private fun chave(id: String, sufixo: String) = "peer:${id.trim().lowercase()}:$sufixo"

    /** Grava as 3 chaves do par: `verificado`, `apelido`, `visto`. */
    @Synchronized
    fun travar(id: String, apelido: String) {
        val k = id.trim().lowercase()
        p().edit()
            .putBoolean(chave(k, "verificado"), true)
            .putString(chave(k, "apelido"), apelido.trim())
            .putLong(chave(k, "visto"), System.currentTimeMillis())
            .apply()
    }

    /** Apaga as 3 chaves: o peer volta a PENDENTE. */
    @Synchronized
    fun destravar(id: String) {
        val k = id.trim().lowercase()
        p().edit()
            .remove(chave(k, "verificado"))
            .remove(chave(k, "apelido"))
            .remove(chave(k, "visto"))
            .apply()
    }

    /** Apelido travado do par, ou `null` se nao travado (ou prefs nao initados). */
    fun apelidoTravado(id: String): String? {
        val p = pOrNull() ?: return null
        if (!p.getBoolean(chave(id, "verificado"), false)) return null
        return p.getString(chave(id, "apelido"), null)?.trim()
    }

    /** Par travado? `false` enquanto [iniciar] nao rodou (nunca lanca). */
    fun verificado(id: String): Boolean =
        pOrNull()?.getBoolean(chave(id, "verificado"), false) ?: false
}
