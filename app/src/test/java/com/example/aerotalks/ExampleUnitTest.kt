package com.example.aerotalks

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 *
 * Testes JVM: round-trip do envelope v1, casos hostis do decode (nunca lanca)
 * e simetria do codigo de 6 digitos.
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    // ------------------------------------------------------------- helpers

    private val eu = BleCodec.deHex("a1b2c3d4e5f60718")!!
    private val outro = BleCodec.deHex("0011223344556677")!!

    /** Envelope valido de [tipo] com [payload], para eu corromper depois. */
    private fun quadro(tipo: Int, payload: ByteArray, dest: ByteArray = BleCodec.ZEROS): ByteArray =
        BleCodec.encode(tipo, eu, dest, payload)

    /**
     * Frame montado NA MAO, sem passar pelas guardas do [BleCodec.encode] — e assim que
     * um peer hostil monta o que ele quer mandar. [payloadLenMentiroso] deixa o campo
     * payloadLen divergir do payload real.
     */
    private fun bruto(
        tipo: Int,
        payload: ByteArray,
        dest: ByteArray = BleCodec.ZEROS,
        payloadLenMentiroso: Int = payload.size
    ): ByteArray {
        val b = ByteArray(BleCodec.HEADER + payload.size)
        b[0] = 0x01
        b[1] = tipo.toByte()
        b[2] = 0x01
        b[11] = 0
        b[12] = ((payloadLenMentiroso ushr 8) and 0xFF).toByte()
        b[13] = (payloadLenMentiroso and 0xFF).toByte()
        eu.copyInto(b, 14)
        dest.copyInto(b, 22)
        payload.copyInto(b, BleCodec.HEADER)
        return b
    }

    // ------------------------------------------------------ round-trip (OK)

    @Test
    fun announce_fazRoundTrip() {
        val apelido = "ana"
        val payload = BleCodec.tlvApelido(apelido)
        val b = quadro(BleCodec.ANNOUNCE, payload)

        // 30 + 2 + 3 = 35 bytes, dentro de 33..53
        assertEquals(35, b.size)
        assertEquals(BleCodec.HEADER, BleCodec.HEADER)

        val env = BleCodec.decode(b)
        assertNotNull("ANNOUNCE valido tem que decodificar", env)
        env!!
        assertEquals(BleCodec.ANNOUNCE, env.tipo)
        assertEquals("a1b2c3d4e5f60718", env.remetHex)
        assertEquals("0000000000000000", env.destHex)
        assertEquals(apelido, BleCodec.apelidoDo(env.payload))
        assertTrue("timestamp deve ser plausivel", env.ts > 1_600_000_000_000L)
    }

    @Test
    fun announce_apelidoCortaEm20Bytes() {
        val payload = BleCodec.tlvApelido("x".repeat(50))
        assertEquals(BleCodec.NICK_MAX + 2, payload.size) // tag + len + 20
        val env = BleCodec.decode(quadro(BleCodec.ANNOUNCE, payload))
        assertNotNull(env)
        assertEquals("x".repeat(20), BleCodec.apelidoDo(env!!.payload))
    }

    @Test
    fun announceTlvDesconhecidoETolerado() {
        // tag 0x02 (que nao deve existir) + apelido 0x01: forward compat, mas volta
        val payload = byteArrayOf(0x02, 0x02, 0x11, 0x22) + BleCodec.tlvApelido("bo")
        val env = BleCodec.decode(quadro(BleCodec.ANNOUNCE, payload))
        assertNotNull("TLV desconhecido no ANNOUNCE deve ser ignorado, nao rejeitado", env)
        assertEquals("bo", BleCodec.apelidoDo(env!!.payload))
    }

    @Test
    fun mensagem_fazRoundTripBroadcast() {
        val texto = "oi,这里是 rede".toByteArray(Charsets.UTF_8)
        val b = quadro(BleCodec.MESSAGE, texto, BleCodec.BROADCAST)

        val env = BleCodec.decode(b)
        assertNotNull(env)
        assertEquals(BleCodec.MESSAGE, env!!.tipo)
        assertEquals("ffffffffffffffff", env.destHex)
        assertEquals(texto.toList(), env.payload.toList())
        assertTrue("broadcast tem que valer para mim", BleCodec.enderecadoParaMim(env, "a1b2c3d4e5f60718"))
    }

    @Test
    fun mensagem_fazRoundTripUnicast() {
        val texto = "oi".toByteArray(Charsets.UTF_8)
        val b = quadro(BleCodec.MESSAGE, texto, outro)
        val env = BleCodec.decode(b)
        assertNotNull(env)
        assertEquals("0011223344556677", env!!.destHex)
        assertTrue(BleCodec.enderecadoParaMim(env, "0011223344556677"))
        assertTrue(BleCodec.enderecadoParaMim(env, "0011223344556677".uppercase())) // normaliza
        assertFalse("nao sou eu", BleCodec.enderecadoParaMim(env, "deadbeefdeadbeef"))
    }

    @Test
    fun mensagem_textoNoLimiteExatoDe140Passa() {
        val texto = "a".repeat(BleCodec.MAX_TEXT).toByteArray(Charsets.UTF_8)
        val env = BleCodec.decode(quadro(BleCodec.MESSAGE, texto))
        assertNotNull("140 bytes exatos tem que passar", env)
        assertEquals(BleCodec.MAX_TEXT, env!!.payload.size)
    }

    @Test
    fun leave_fazRoundTripCom30BytesExatos() {
        val b = quadro(BleCodec.LEAVE, ByteArray(0))
        assertEquals(30, b.size)
        val env = BleCodec.decode(b)
        assertNotNull(env)
        assertEquals(BleCodec.LEAVE, env!!.tipo)
        assertEquals(0, env.payload.size)
        assertEquals("0000000000000000", env.destHex)
    }

    @Test
    fun encodeEscreveOsOffsetsDoProtocolo() {
        val texto = "ab".toByteArray(Charsets.UTF_8)
        val b = BleCodec.encode(BleCodec.MESSAGE, eu, BleCodec.BROADCAST, texto, flags = 1)

        assertEquals(BleCodec.HEADER + texto.size, b.size)
        assertEquals(0x01, b[0].toInt()) // version
        assertEquals(BleCodec.MESSAGE, b[1].toInt()) // type
        assertEquals(0x01, b[2].toInt()) // ttl
        assertEquals(1, b[11].toInt()) // flags
        assertEquals(0, b[12].toInt()) // payloadLen BE (alta)
        assertEquals(texto.size, b[13].toInt()) // payloadLen BE (baixa)
        assertEquals("a1b2c3d4e5f60718", BleCodec.hex(b, 14, BleCodec.ID_LEN)) // senderID
        assertEquals("ffffffffffffffff", BleCodec.hex(b, 22, BleCodec.ID_LEN)) // recipientID
        assertEquals(texto.toList(), b.copyOfRange(BleCodec.HEADER, b.size).toList()) // payload
    }

    @Test
    fun idsComTodosOsBytesSobrevivemAoRoundTrip() {
        val id = ByteArray(BleCodec.ID_LEN) { (it * 17 + 3).toByte() }
        val b = BleCodec.encode(BleCodec.MESSAGE, id, BleCodec.BROADCAST, "x".toByteArray())
        val env = BleCodec.decode(b)
        assertNotNull(env)
        assertEquals(BleCodec.hex(id, 0, id.size), env!!.remetHex)
        assertEquals(id.toList(), BleCodec.deHexId(env.remetHex)!!.toList())
    }

    @Test
    fun encodeNaoTruncaTextoLongoEmSilencio() {
        try {
            BleCodec.encode(BleCodec.MESSAGE, eu, BleCodec.BROADCAST, "a".repeat(BleCodec.MAX_TEXT + 1).toByteArray())
            fail("texto acima de 140 tem que ser recusado, nao truncado")
        } catch (e: IllegalArgumentException) {
            // esperado: o controlador BLE checa antes e mostra toast
        }
    }

    @Test
    fun hexVoltaAoOriginal() {
        val todos = ByteArray(256) { it.toByte() }
        assertEquals(todos.toList(), BleCodec.deHex(BleCodec.hex(todos))!!.toList())
        assertEquals("a1b2c3d4e5f60718", BleCodec.hex(eu, 0, eu.size))
        assertNotNull(BleCodec.deHexId("a1b2c3d4e5f60718"))
        assertNull("hex impar", BleCodec.deHex("abc"))
        assertNull("hex invalido", BleCodec.deHex("zzzz"))
        assertNull("id de tamanho errado", BleCodec.deHexId("a1b2"))
    }

    // ------------------------------------------------------- casos hostis

    @Test
    fun hostil_arrayVazioEDe29Bytes() {
        assertNull(BleCodec.decode(ByteArray(0)))
        assertNull(BleCodec.decode(ByteArray(29))) // 1 byte abaixo do piso
        assertNull(BleCodec.decode(ByteArray(5)))
    }

    @Test
    fun hostil_acimaDoTetoDe200() {
        // 30 + 171 = 201 bytes: estoura o teto duro, nem olha o resto
        assertNull(BleCodec.decode(ByteArray(201)))
        assertNull(BleCodec.decode(ByteArray(512)))
        // 30 + 140 = 170: o maior envelope legitimo, aceito
        val maximo = "a".repeat(BleCodec.MAX_TEXT).toByteArray(Charsets.UTF_8)
        assertEquals(170, BleCodec.HEADER + maximo.size)
        assertNotNull(BleCodec.decode(quadro(BleCodec.MESSAGE, maximo)))
        // 171: passa do teto do tipo (MESSAGE <= 140) e morre la
        assertNull(BleCodec.decode(bruto(BleCodec.MESSAGE, "a".repeat(141).toByteArray())))
    }

    @Test
    fun hostil_size30DeZeros() {
        // piso exato, mas version = 0x00 => drop
        assertEquals(30, BleCodec.HEADER)
        assertNull(BleCodec.decode(ByteArray(30)))
    }

    @Test
    fun hostil_versionInvalida() {
        val b = quadro(BleCodec.LEAVE, ByteArray(0))
        b[0] = 0x02
        assertNull(BleCodec.decode(b))
    }

    @Test
    fun hostil_tipoDesconhecido() {
        val b = quadro(BleCodec.LEAVE, ByteArray(0))
        b[1] = 0x09
        assertNull(BleCodec.decode(b))
        b[1] = 0x00
        assertNull(BleCodec.decode(b))
    }

    @Test
    fun hostil_ttlAlterado() {
        val b = quadro(BleCodec.LEAVE, ByteArray(0))
        b[2] = 0x05
        assertNull(BleCodec.decode(b))
    }

    @Test
    fun hostil_payloadLenMentiroso() {
        // declara 5 bytes num frame de 30 (payload real = 0)
        val b = quadro(BleCodec.MESSAGE, "abcde".toByteArray(Charsets.UTF_8))
        b[12] = 0x00
        b[13] = 0x00
        assertNull(BleCodec.decode(b))

        // declara menos do que veio (write truncado)
        val c = quadro(BleCodec.MESSAGE, "abcde".toByteArray(Charsets.UTF_8))
        c[13] = 0x02
        assertNull(BleCodec.decode(c))

        // declara mais do que veio
        val d = quadro(BleCodec.MESSAGE, "abcde".toByteArray(Charsets.UTF_8))
        d[12] = 0x7F
        d[13] = 0xFF.toByte()
        assertNull(BleCodec.decode(d))
    }

    @Test
    fun hostil_mensagemAcimaDe140() {
        // 30 + 141 = 171: passa do teto do tamanho, morre na regra do MESSAGE
        val texto = "a".repeat(BleCodec.MAX_TEXT + 1).toByteArray(Charsets.UTF_8)
        assertEquals(171, BleCodec.HEADER + texto.size)
        assertNull(BleCodec.decode(bruto(BleCodec.MESSAGE, texto)))
        // e um payloadLen que bate com o payload gigante ainda nao salva
        assertNull(BleCodec.decode(bruto(BleCodec.MESSAGE, texto, BleCodec.ZEROS, payloadLenMentiroso = texto.size)))
    }

    @Test
    fun hostil_tlvAnunciandoLen255() {
        // tag 0x01 + len 0xFF, mas so ha 3 bytes depois
        val payload = byteArrayOf(0x01, 0xFF.toByte(), 0x41, 0x42, 0x43)
        assertNull(BleCodec.decode(quadro(BleCodec.ANNOUNCE, payload)))
        // e com 0 bytes depois
        assertNull(BleCodec.decode(quadro(BleCodec.ANNOUNCE, byteArrayOf(0x01, 0xFF.toByte()))))
        // apelidoDo tambem tem de devolver null, nunca estourar
        assertNull(BleCodec.apelidoDo(payload))
    }

    @Test
    fun hostil_tlvTagSemLen() {
        assertNull(BleCodec.decode(quadro(BleCodec.ANNOUNCE, byteArrayOf(0x01))))
        assertNull(BleCodec.apelidoDo(byteArrayOf(0x01)))
    }

    @Test
    fun hostil_tlvTruncadoNoMeioDoValor() {
        // len = 10, valor tem 4
        assertNull(BleCodec.decode(quadro(BleCodec.ANNOUNCE, byteArrayOf(0x01, 0x0A, 0x41, 0x42, 0x43, 0x44))))
    }

    @Test
    fun hostil_leaveComPayload() {
        assertNull(BleCodec.decode(quadro(BleCodec.LEAVE, byteArrayOf(0x01, 0x00))))
        assertNull(BleCodec.decode(quadro(BleCodec.LEAVE, byteArrayOf(0x41))))
    }

    @Test
    fun hostil_decodeNuncaLanca() {
        // varredura em ceu de bytes aleatorios: nenhuma pode estourar
        val alvos = listOf(
            ByteArray(0),
            ByteArray(29),
            ByteArray(30) { 0xFF.toByte() },
            ByteArray(31) { 0xFF.toByte() },
            ByteArray(200) { 0xFF.toByte() },
            ByteArray(201) { 0xFF.toByte() },
            quadro(BleCodec.ANNOUNCE, ByteArray(40) { 0x7F }),
            quadro(BleCodec.ANNOUNCE, byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        )
        for (b in alvos) {
            val r = try { BleCodec.decode(b) } catch (t: Throwable) { fail("decode lancou em ${b.size} bytes: $t") }
            assertNull("frame hostil de ${b.size} bytes tem que dar null", r)
        }
    }

    @Test
    fun hostil_fuzzDeterministicoNaoLanca() {
        // LCG com semente fixa: mesmo conjunto de bytes em toda roda, sem Math.random.
        var s = 0x5DEECE66DL
        fun proximo(): Int {
            s = (s * 25214903917L + 11L) and ((1L shl 48) - 1)
            return ((s ushr 16).toInt() and 0x7FFFFFFF)
        }
        for (i in 0 until 5000) {
            val n = proximo() % 260 // inclui 0, 29, 30, 200, 201 e o resto
            val b = ByteArray(n) { (proximo() and 0xFF).toByte() }
            // metade das vezes: cabecalho valido, o resto corrompido (pior caso)
            if (i % 2 == 0 && n >= BleCodec.HEADER) {
                b[0] = 0x01
                b[1] = byteArrayOf(1, 2, 3)[proximo() % 3]
                b[2] = 0x01
                b[12] = ((n - 30) ushr 8).toByte()
                b[13] = ((n - 30) and 0xFF).toByte()
            }
            val r: BleCodec.Envelope? = try {
                BleCodec.decode(b)
            } catch (t: Throwable) {
                fail("decode lancou em ${b.size} bytes (iteracao $i): $t")
                null
            }
            if (r != null) {
                // o que passa tem de ser coerente: payload bate com payloadLen
                assertEquals(b.size - BleCodec.HEADER, r.payload.size)
                assertEquals(16, r.remetHex.length)
                assertEquals(16, r.destHex.length)
            }
        }
    }

    // ------------------------------------------------ codigo de verificacao

    @Test
    fun codigoDe6DigitosEFormatado() {
        val c = IdentidadeStore.codigoSimetrico("a1b2c3d4e5f60718", "0011223344556677")
        assertEquals(6, c.length)
        assertTrue("so digitos: $c", c.all { it in '0'..'9' })
        assertEquals(c, IdentidadeStore.codigoSimetrico("a1b2c3d4e5f60718", "0011223344556677"))
    }

    @Test
    fun codigoDe6DigitosEhSimetrico() {
        val a = "a1b2c3d4e5f60718"
        val b = "0011223344556677"
        assertEquals(IdentidadeStore.codigoSimetrico(a, b), IdentidadeStore.codigoSimetrico(b, a))
        // e insensivel a caixa
        assertEquals(
            IdentidadeStore.codigoSimetrico(a, b),
            IdentidadeStore.codigoSimetrico(a.uppercase(), b.uppercase())
        )
    }

    @Test
    fun codigoMudaComIdDiferente() {
        val base = IdentidadeStore.codigoSimetrico("a1b2c3d4e5f60718", "0011223344556677")
        assertNotEquals(base, IdentidadeStore.codigoSimetrico("a1b2c3d4e5f60718", "0011223344556678"))
        assertNotEquals(base, IdentidadeStore.codigoSimetrico("a1b2c3d4e5f60719", "0011223344556677"))
    }

    @Test
    fun codigoComIdVazioNaoEUmCodigo() {
        assertEquals("------", IdentidadeStore.codigoSimetrico("", "0011223344556677"))
        assertEquals("------", IdentidadeStore.codigoSimetrico("a1b2c3d4e5f60718", "   "))
    }

    // ------------------------------------------------------------- PeerTable

    @Test
    fun peerTableRegistraEExpira() {
        PeerTable.limpar()
        val p = PeerTable.registrar("A1B2C3D4E5F60718", "ana", -47)
        assertEquals("a1b2c3d4e5f60718", p.id) // normaliza para minusculo
        assertEquals("ana", p.nickname)
        assertTrue(p.conectado)
        assertEquals(-47, p.rssi)

        assertEquals(1, PeerTable.peers().size)
        assertTrue(PeerTable.contem("a1b2c3d4e5f60718"))

        // avanca o relogio alem de 180 s: desconecta
        assertEquals(1, PeerTable.expirar(System.currentTimeMillis() + PeerTable.EXPIRA_MS + 1))
        assertFalse(PeerTable.peers().first().conectado)

        // tocar revive
        PeerTable.tocar("a1b2c3d4e5f60718")
        assertTrue(PeerTable.peers().first().conectado)

        // dentro da janela nao expira
        assertEquals(0, PeerTable.expirar(System.currentTimeMillis() + 1000))

        PeerTable.remover("a1b2c3d4e5f60718")
        assertEquals(0, PeerTable.peers().size)
        PeerTable.limpar()
    }

    @Test
    fun peerTableIgnoraIdVazio() {
        PeerTable.limpar()
        PeerTable.registrar("  ", "sem id", -80)
        assertEquals(0, PeerTable.peers().size)
    }

    @Test
    fun peerTableTocarDeDesconhecidoNaoCria() {
        PeerTable.limpar()
        PeerTable.tocar("ffffffffffffffff")
        assertEquals(0, PeerTable.peers().size)
        assertNull(PeerTable.lastSeenDe("ffffffffffffffff"))
    }

    @Test
    fun acoesDeBroadcastSaoDistintas() {
        assertEquals("com.example.aerotalks.PEERS_CHANGED", PeerTable.ACTION_PEERS_CHANGED)
        assertEquals("com.example.aerotalks.MENSAGEM", PeerTable.ACTION_MENSAGEM)
    }
}
