package com.example.aerotalks

/** Nó vizinho do mesh (simulado). */
data class Peer(
    val id: String,
    val nickname: String,
    val conectado: Boolean,
    val verificado: Boolean,
    val favorito: Boolean,
    val rssi: Int,
    val hops: Int
) {
    val status: String
        get() = buildString {
            append(if (conectado) "CONECTADO" else "DESCONECTADO")
            if (verificado) append(" · VERIFICADO")
            append(" · $rssi dBm")
            append(" · $hops saltos")
        }
}