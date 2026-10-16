package com.example.aerotalks

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/**
 * Tela 2 — Apelido (design §4): tela inteira que substitui o AlertDialog
 * "Como você quer ser chamado?" do portão de entrada.
 *
 * Sem regra de validação própria: o vazio continua tratado em
 * [IdentidadeStore.setApelido] (volta para o default). O botão voltar do sistema
 * tem o comportamento padrão (volta para o Início) — não trancamos a tela.
 */
class ApelidoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apelido)

        // iniciar antes de ler/escrever o apelido (mesmo padrão das outras telas).
        IdentidadeStore.iniciar(this)

        val campo = findViewById<EditText>(R.id.etApelido)
        val btnEntrar = findViewById<Button>(R.id.btnEntrar)

        campo.setText(IdentidadeStore.apelido)

        btnEntrar.setOnClickListener {
            IdentidadeStore.setApelido(campo.text.toString())
            startActivity(Intent(this, AgendaActivity::class.java))
            // finish: o botão voltar da Conversa não deve reabrir o apelido.
            finish()
        }
    }
}
