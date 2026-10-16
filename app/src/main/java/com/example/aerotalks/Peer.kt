package com.example.aerotalks

/**
 * Linha da mesa do mesh: id, apelido, sinal e o par travado.
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