package com.example.aerotalks

data class Evento(
    val id: Int = 0,
    val nome: String,
    val data: String,
    val local: String,
    val descricao: String,
    val favorito: Boolean = false
)