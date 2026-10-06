package com.example.aerotalks

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CadastroEventoActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cadastro_evento)

        val etNome = findViewById<EditText>(R.id.etNome)
        val etData = findViewById<EditText>(R.id.etData)
        val etLocal = findViewById<EditText>(R.id.etLocal)
        val etDescricao = findViewById<EditText>(R.id.etDescricao)
        val btnSalvar = findViewById<Button>(R.id.btnSalvar)

        btnSalvar.setOnClickListener {
            val evento = Evento(
                nome = etNome.text.toString().trim(),
                data = etData.text.toString().trim(),
                local = etLocal.text.toString().trim(),
                descricao = etDescricao.text.toString().trim()
            )

            if (evento.nome.isEmpty()) {
                etNome.error = "Informe o nome"
                return@setOnClickListener
            }

            val banco = BancoHelper(this)
            banco.inserir(evento)
            Toast.makeText(this, "Evento salvo!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}