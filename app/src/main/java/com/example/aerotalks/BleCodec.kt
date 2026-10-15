package com.example.aerotalks

/**
 * Codec do envelope v1 da malha BLE. Kotlin puro: nenhuma dependencia de Android,
 * o que torna o round-trip testavel em unit test no host.
 *
 * Layout (30 B de cabecalho fixo, multi-byte sempre big-endian):
 *
 * | off | tam | campo        | notas                                                |
 * |-----|-----|--------------|------------------------------------------------------|
 * | 0   | 1   | version      | 0x01; qualquer outro valor cai                        |
 * | 1   | 1   | type         | 1 ANNOUNCE, 2 MESSAGE, 3 LEAVE                        |
 * | 2   | 1   | ttl          | 0x01 (sem relay no v1)                               |
 * | 3   | 8   | timestamp    | epoch millis, BE                                      |
 * | 11  | 1   | flags        | bit0 = resposta a ANNOUNCE                           |
 * | 12  | 2   | payloadLen   | BE; tem de bater com size - 30                       |
 * | 14  | 8   | senderID     | primeiros 8 bytes do SHA-256 do secret                |
 * | 22  | 8   | recipientID  | MESSAGE: FFx8 (broadcast) ou peerID; resto: 00x8       |
 * | 30  | N   | payload      | ver [tlvApelido] / texto UTF-8 cru                    |
 *
 * R1 (anti peer hostil): [decode] NUNCA lanca. Toda rejeicao devolve `null`
 * (nunca `require`/`check`), e toda leitura e sempre precedida deBounds check.
 */
object BleCodec {

    /** Tamanho do cabecalho fixo. */
    const val HEADER = 30

    /** Unica versao de wire aceita. */
    const val VERSION = 0x01

    /** TTL fixo do v1 (sem relay). */
    const val TTL = 0x01

    // --- tipos -------------------------------------------------------------
    const val ANNOUNCE = 1
    const val MESSAGE = 2
    const val LEAVE = 3

    // --- limites duros -----------------------------------------------------
    /** Piso: write curto quebra a leitura de offset. */
    const val MIN_FRAME = HEADER

    /** Teto: link MTU 517 aceitaria 484 B; acima disso ja nao e nosso. */
    const val MAX_FRAME = 200

    /** Payload maximo de MESSAGE (texto UTF-8). */
    const val MAX_TEXT = 140

    /** Apelido maximo em BYTES de UTF-8 (corta em byte, nao em caractere). */
    const val NICK_MAX = 20

    /** Tamanho de senderID / recipientID. */
    const val ID_LEN = 8

    // --- TLV (so em ANNOUNCE) ---------------------------------------------
    /** tag do apelido. Nao existe tag 0x02: o codigo de verificacao e local. */
    const val TLV_NICK = 0x01

    // Constantes internas: o `val` publico abaixo devolve COPIA, entao ninguem
    // (nem a propria classe via System.arraycopy) altera o array compartilhado.
    private val BROADCAST_BYTES: ByteArray = ByteArray(ID_LEN) { 0xFF.toByte() }
    private val ZEROS_BYTES: ByteArray = ByteArray(ID_LEN) { 0 }

    /** recipientID = BROADCAST: 8 bytes 0xFF (copia nova a cada leitura). */
    val BROADCAST: ByteArray get() = BROADCAST_BYTES.copyOf()

    /** recipientID de ANNOUNCE/LEAVE: 8 bytes 0x00 (copia nova a cada leitura). */
    val ZEROS: ByteArray get() = ZEROS_BYTES.copyOf()

    private const val OFF_VERSION = 0
    private const val OFF_TIPO = 1
    private const val OFF_TTL = 2
    private const val OFF_TS = 3
    private const val OFF_FLAGS = 11
    private const val OFF_LEN = 12
    private const val OFF_REMETENTE = 14
    private const val OFF_DESTINATARIO = 22

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * Envelope decodificado. [remetHex]/[destHex] sao hex minusculo de 16 chars.
     * [payload] e ByteArray: `equals` de data class compara referencia (assim mesmo).
     */
    data class Envelope(
        val tipo: Int,
        val ts: Long,
        val remetHex: String,
        val destHex: String,
        val payload: ByteArray
    )

    // ------------------------------------------------------------------ wire

    /**
     * Monta o envelope. [remetente]/[destinatario] tem de ter exatamente [ID_LEN] bytes.
     *
     * Lanca [IllegalArgumentException] em erro de programacao (tipo fora de 1..3, id com
     * tamanho errado, payload que nao cabe no envelope). O caller (controlador BLE, lane A) tem de
     * validar o texto antes — design §4: acima de [MAX_TEXT] vira toast, nunca truncamento silencioso.
     */
    fun encode(
        tipo: Int,
        remetente: ByteArray,
        destinatario: ByteArray,
        payload: ByteArray,
        flags: Int = 0
    ): ByteArray {
        if (tipo < ANNOUNCE || tipo > LEAVE) throw IllegalArgumentException("tipo fora de $ANNOUNCE..$LEAVE, veio $tipo")
        if (remetente.size != ID_LEN) throw IllegalArgumentException("remetente precisa de 8 bytes, veio ${remetente.size}")
        if (destinatario.size != ID_LEN) throw IllegalArgumentException("destinatario precisa de 8 bytes, veio ${destinatario.size}")
        if (payload.size > 0xFFFF) throw IllegalArgumentException("payload acima de 65535 bytes")
        if (HEADER + payload.size > MAX_FRAME) throw IllegalArgumentException("envelope acima de $MAX_FRAME bytes")
        if (tipo == MESSAGE && payload.size > MAX_TEXT) throw IllegalArgumentException("MESSAGE acima de $MAX_TEXT bytes")

        val out = ByteArray(HEADER + payload.size)
        out[OFF_VERSION] = VERSION.toByte()
        out[OFF_TIPO] = tipo.toByte()
        out[OFF_TTL] = TTL.toByte()

        var ts = System.currentTimeMillis()
        for (i in 7 downTo 0) {
            out[OFF_TS + i] = (ts and 0xFF).toByte()
            ts = ts ushr 8
        }

        out[OFF_FLAGS] = flags.toByte()

        val n = payload.size
        out[OFF_LEN] = ((n ushr 8) and 0xFF).toByte()
        out[OFF_LEN + 1] = (n and 0xFF).toByte()

        System.arraycopy(remetente, 0, out, OFF_REMETENTE, ID_LEN)
        System.arraycopy(destinatario, 0, out, OFF_DESTINATARIO, ID_LEN)
        System.arraycopy(payload, 0, out, HEADER, n)
        return out
    }

    /**
     * Decodifica. Devolve `null` (e NUNCA lanca) para qualquer coisa fora do contrato:
     * tamanho fora de 30..200, version/tipo invalidos, `payloadLen` mentiroso,
     * MESSAGE acima de [MAX_TEXT], LEAVE com payload ou TLV de ANNOUNCE quebrado.
     */
    fun decode(b: ByteArray): Envelope? {
        // piso e teto ANTES de qualquer leitura por offset (R1)
        if (b.size < MIN_FRAME || b.size > MAX_FRAME) return null
        if (u(b[OFF_VERSION]) != VERSION) return null

        val tipo = u(b[OFF_TIPO])
        if (tipo != ANNOUNCE && tipo != MESSAGE && tipo != LEAVE) return null
        if (u(b[OFF_TTL]) != TTL) return null

        val declarado = (u(b[OFF_LEN]) shl 8) or u(b[OFF_LEN + 1])
        if (declarado != b.size - HEADER) return null

        val payload = b.copyOfRange(HEADER, b.size)

        when (tipo) {
            MESSAGE -> if (payload.size > MAX_TEXT) return null
            LEAVE -> if (payload.isNotEmpty()) return null
            ANNOUNCE -> if (!tlvIntegro(payload)) return null
        }

        return Envelope(
            tipo = tipo,
            ts = lerTs(b),
            remetHex = hex(b, OFF_REMETENTE, ID_LEN),
            destHex = hex(b, OFF_DESTINATARIO, ID_LEN),
            payload = payload
        )
    }

    /** Conveniencia: o frame e para mim? (broadcast ou meu peerID). */
    fun enderecadoParaMim(env: Envelope?, meuId: String?): Boolean {
        if (env == null || meuId == null) return false
        if (env.destHex == hex(BROADCAST_BYTES, 0, ID_LEN)) return true
        return env.destHex.equals(meuId.trim().lowercase(), ignoreCase = true)
    }

    // -------------------------------------------------------------------- TLV

    /**
     * Payload de ANNOUNCE: um unico TLV `0x01 nickname`, com o apelido cortado em
     * [NICK_MAX] BYTES de UTF-8 (corte em byte, nao em limite de caractere).
     */
    fun tlvApelido(apelido: String): ByteArray {
        val bruto = apelido.trim().toByteArray(Charsets.UTF_8)
        val cortado = if (bruto.size > NICK_MAX) bruto.copyOf(NICK_MAX) else bruto
        val out = ByteArray(2 + cortado.size)
        out[0] = TLV_NICK.toByte()
        out[1] = cortado.size.toByte()
        System.arraycopy(cortado, 0, out, 2, cortado.size)
        return out
    }

    /** Apelido de um payload de ANNOUNCE, ou `null` se nao houver TLV 0x01. */
    fun apelidoDo(payload: ByteArray): String? {
        var cursor = 0
        while (cursor < payload.size) {
            if (cursor + 2 > payload.size) return null
            val tag = u(payload[cursor])
            val len = u(payload[cursor + 1])
            if (cursor + 2 + len > payload.size) return null
            if (tag == TLV_NICK) return String(payload, cursor + 2, len, Charsets.UTF_8)
            cursor += 2 + len
        }
        return null
    }

    /** Cada TLV cabe inteiro no payload? (o cursor nunca passa do fim). */
    private fun tlvIntegro(payload: ByteArray): Boolean {
        var cursor = 0
        while (cursor < payload.size) {
            if (cursor + 2 > payload.size) return false
            val len = u(payload[cursor + 1])
            if (cursor + 2 + len > payload.size) return false
            cursor += 2 + len
        }
        return true
    }

    // ------------------------------------------------------------------- hex

    /** Hex minusculo de [len] bytes a partir de [offset]. */
    fun hex(b: ByteArray, offset: Int, len: Int): String {
        if (offset < 0 || len < 0 || offset + len > b.size) return ""
        val out = CharArray(len * 2)
        for (i in 0 until len) {
            val v = u(b[offset + i])
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    /** Hex minusculo de um array inteiro. */
    fun hex(b: ByteArray): String = hex(b, 0, b.size)

    /** Hex -> bytes. `null` se nao for hex par com os chars certos (nunca lanca). */
    fun deHex(h: String): ByteArray? {
        val limpo = h.trim()
        if (limpo.length % 2 != 0) return null
        val out = ByteArray(limpo.length / 2)
        for (i in out.indices) {
            val hi = nibble(limpo[i * 2])
            val lo = nibble(limpo[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** Hex -> [ID_LEN] bytes, ou `null` se nao der. */
    fun deHexId(h: String): ByteArray? {
        val b = deHex(h) ?: return null
        if (b.size != ID_LEN) return null
        return b
    }

    // --------------------------------------------------------------- interno

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    private fun lerTs(b: ByteArray): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or u(b[OFF_TS + i]).toLong()
        return v
    }

    private fun nibble(c: Char): Int {
        return when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }
}
