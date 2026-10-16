package com.example.aerotalks

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/**
 * Tela 2 — Apelido. O vazio e tratado em [IdentidadeStore.setApelido]; o botao
 * voltar do sistema tem o comportamento padrao.
 */
class ApelidoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apelido)

        // Insets do sistema no root.
        findViewById<ViewGroup>(android.R.id.content).getChildAt(0).aplicarInsetsDoSistema()

        // Inicia o store antes de ler/escrever o apelido.
        IdentidadeStore.iniciar(this)

        val campo = findViewById<EditText>(R.id.etApelido)
        val btnEntrar = findViewById<Button>(R.id.btnEntrar)

        campo.setText(IdentidadeStore.apelido)

        btnEntrar.setOnClickListener {
            IdentidadeStore.setApelido(campo.text.toString())
            startActivity(Intent(this, AgendaActivity::class.java))
            // finish: encerra a tela para o voltar da Conversa nao reabrir o apelido.
            finish()
        }
    }
}
