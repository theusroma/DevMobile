package com.example.aerotalks

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Finge um mesh local: vizinhos, canais e mensagens que chegam sozinhas.
 * Nada aqui fala com rádio nenhum — é tudo sorteio local.
 */
object MeshSimulator {

    /** Meu nick no simulador. */
    val eu: String = "voador"

    /** Meu id de nó falso (16 hex). */
    val meuId: String = "a7f31c04be92d518"

    /** Hora atual no formato curto. */
    fun agora(): String = relogio().format(Date())

    /** Hora de "N horas atrás", para a semeadura. */
    fun horasAtras(h: Int): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.HOUR_OF_DAY, -h)
        return relogio().format(cal.time)
    }

    private fun relogio() = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun peers(): List<Peer> = listOf(
        Peer("3f0a91c7d2e4b615", "ana", conectado = true, verificado = true, favorito = true, rssi = -47, hops = 1),
        Peer("b8214ee0c93a77f2", "bruno", conectado = true, verificado = false, favorito = false, rssi = -63, hops = 2),
        Peer("55c8d1a907fe32b4", "carla", conectado = false, verificado = true, favorito = false, rssi = -88, hops = 3),
        Peer("9d2f66ab04c1e873", "davi", conectado = true, verificado = false, favorito = true, rssi = -55, hops = 1),
        Peer("e740bb39c2158d60", "elisa", conectado = true, verificado = true, favorito = false, rssi = -71, hops = 2),
        Peer("14ac5837b0d9fe26", "fábio", conectado = false, verificado = false, favorito = false, rssi = -90, hops = 3),
        Peer("cb39e8157a604df2", "gabi", conectado = true, verificado = false, favorito = false, rssi = -58, hops = 2),
        Peer("76e0d4a9f31c8b50", "heitor", conectado = true, verificado = true, favorito = false, rssi = -66, hops = 1)
    )

    fun canais(): List<Canal> = listOf(
        Canal("geral", dentro = true, membros = 5, protegido = false),
        Canal("cafeteria", dentro = true, membros = 3, protegido = false),
        Canal("prox-unicamp", dentro = true, membros = 4, protegido = false),
        Canal("cripto", dentro = false, membros = 2, protegido = true)
    )

    /** 5 mensagens que "chegaram" antes de a tela abrir. */
    fun semear(): List<Mensagem> = listOf(
        Mensagem(
            autor = "ana",
            conteudo = "bom dia, mesh viva!",
            canal = "geral",
            hora = horasAtras(3),
            minha = false
        ),
        Mensagem(
            autor = "bruno",
            conteudo = "alguém viu a prova de redes hoje?",
            canal = "geral",
            hora = horasAtras(2),
            minha = false
        ),
        Mensagem(
            autor = "carla",
            conteudo = "cafeteria está aberta, venham",
            canal = "cafeteria",
            hora = horasAtras(2),
            minha = false
        ),
        Mensagem(
            autor = "gabi",
            conteudo = "voador, te mandei o material no privado",
            canal = "geral",
            privadoPara = eu,
            hora = horasAtras(1),
            minha = false
        ),
        Mensagem(
            autor = "elisa",
            conteudo = "@voador dá uma olhada no anexo quando puder",
            canal = "prox-unicamp",
            hora = horasAtras(1),
            minha = false,
            mencao = true
        )
    )

    private val frases = listOf(
        "opa, recebi o pacote",
        "alguém por perto da biblioteca?",
        "roteando pelo campus principal",
        "esse canal tá quieto demais",
        "testando o repasse no #geral",
        "vi o aviso, já tô indo",
        "sinal meio ruim aqui, mas cheguei",
        "quem entrou no mesh do #prox-unicamp?",
        "boa, valeu",
        "meu nó caiu e voltou, tudo certo"
    )

    /**
     * Chega uma mensagem nova do mesh na maioria das vezes (~70%).
     * Devolve null quando o mesh fica em silêncio — o canal fica quieto.
     */
    fun proxima(): Mensagem? {
        if (Random.nextInt(100) >= 70) return null

        val peer = peers()[Random.nextInt(0, peers().size)]
        val canal = canais()[Random.nextInt(0, canais().size)].nome
        val privada = Random.nextInt(100) < 15

        return Mensagem(
            autor = peer.nickname,
            conteudo = if (privada) "te mandei no privado, vê aí" else frases[Random.nextInt(0, frases.size)],
            canal = if (privada) "geral" else canal,
            privadoPara = if (privada) eu else null,
            hora = agora(),
            minha = false,
            entregue = Random.nextInt(100) < 92,
            mencao = Random.nextInt(100) < 12
        )
    }
}