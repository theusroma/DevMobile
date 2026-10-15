package com.example.aerotalks

/**
 * Linha da mesa do mesh (design §5): id, apelido, sinal e o par travado.
 * Sem `favorito` (nunca lido) e sem `hops` (o v1 nao faz relay).
 */
data class Peer(
    val id: String,
    val nickname: String,
    val conectado: Boolean,
    val verificado: Boolean,
    val rssi: Int
) {
    val status: String
        get() = buildString {
            append(if (conectado) "CONECTADO" else "DESCONECTADO")
            if (verificado) append(" · VERIFICADO")
            append(" · $rssi dBm")
        }
}