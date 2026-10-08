package com.example.aerotalks

/** Canal do mesh. */
data class Canal(
    val nome: String,
    val dentro: Boolean,
    val membros: Int,
    val protegido: Boolean
) {
    val resumo: String
        get() = buildString {
            append("#$nome")
            append(" · $membros membros")
            if (protegido) append(" · PROTEGIDO")
        }
}