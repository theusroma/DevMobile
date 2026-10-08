package com.example.aerotalks

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Escrever no mesh. Campos reaproveitados:
 * etNome = mensagem, etData = canal, etLocal = privado para, etDescricao = nota.
 * Sem comandos: ENVIAR sempre manda texto simples no canal do campo.
 */
class CadastroEventoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cadastro_evento)

        val etNome = findViewById<EditText>(R.id.etNome)
        val etData = findViewById<EditText>(R.id.etData)
        val etLocal = findViewById<EditText>(R.id.etLocal)
        val etDescricao = findViewById<EditText>(R.id.etDescricao)
        val btnSalvar = findViewById<Button>(R.id.btnSalvar)

        val canalPre = intent.getStringExtra("canal") ?: "geral"
        etData.setText("#" + canalPre.removePrefix("#").ifEmpty { "geral" })

        btnSalvar.setOnClickListener {
            val texto = etNome.text.toString().trim()
            val canal = etData.text.toString().trim().removePrefix("#").lowercase(Locale.getDefault())
                .ifEmpty { "geral" }
            val privadoPara = etLocal.text.toString().trim().removePrefix("@").lowercase(Locale.getDefault())
                .ifEmpty { null }
            val nota = etDescricao.text.toString().trim()

            if (texto.isEmpty()) {
                etNome.error = "Informe a mensagem"
                return@setOnClickListener
            }

            val conteudo = if (nota.isEmpty()) texto else "$texto\n[$nota]"
            val store = MeshStore(this)
            store.inserir(
                Mensagem(
                    autor = MeshSimulator.eu,
                    conteudo = conteudo,
                    canal = canal,
                    privadoPara = privadoPara,
                    hora = MeshSimulator.agora(),
                    minha = true
                )
            )

            Toast.makeText(this, "Mensagem enviada", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}