package com.example.aerotalks

/** Uma linha da transcrição do mesh. */
data class Mensagem(
    val id: Int = 0,
    val autor: String,
    val conteudo: String,
    val canal: String = "geral",
    val privadoPara: String? = null,
    val hora: String,
    val minha: Boolean,
    val entregue: Boolean = true,
    val mencao: Boolean = false
) {
    val ehPrivada: Boolean get() = privadoPara != null
}