package com.example.aerotalks

import android.os.Bundle
import android.text.InputFilter
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Nova mensagem: grava local e manda no fio (design §5).
 *
 * **Canal e nota sao LOCAIS**: no envelope so vai o texto puro, porque `canal` e
 * conceito de exibicao e o v1 nao trafega metadado.
 */
class CadastroEventoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cadastro_evento)

        IdentidadeStore.iniciar(this)

        val etNome = findViewById<EditText>(R.id.etNome)
        val etData = findViewById<EditText>(R.id.etData)
        val etLocal = findViewById<EditText>(R.id.etLocal)
        etLocal.hint = "Privado para: id de 16 hex do vizinho (ver REDE)"
        val etDescricao = findViewById<EditText>(R.id.etDescricao)
        val btnSalvar = findViewById<Button>(R.id.btnSalvar)

        // Teto de caracteres na UI; a checagem dura e de BYTES UTF-8 (abaixo).
        etNome.filters = arrayOf(InputFilter.LengthFilter(BleMesh.MAX_TEXT))

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
            if (privadoPara != null && !privadoPara.matches(PEER_ID)) {
                etLocal.error = "ID do vizinho: 16 caracteres hex (ex: 3f0a91c7d2e4b615)"
                return@setOnClickListener
            }

            // Checagem dura de bytes: 140 caracteres podem passar do limite de 140 bytes
            // em acentos/emoji, e o BleMesh recusa sem truncar.
            val bytes = texto.toByteArray(Charsets.UTF_8).size
            val longaDemais = bytes > BleMesh.MAX_TEXT

            // No fio vai SO o texto: canal e nota continuam locais.
            val saiu = !longaDemais && BleMesh.enviar(texto, privadoPara)

            val conteudo = if (nota.isEmpty()) texto else "$texto\n[$nota]"
            MeshStore(this).inserir(
                Mensagem(
                    autor = IdentidadeStore.apelido,
                    conteudo = conteudo,
                    canal = canal,
                    privadoPara = privadoPara,
                    hora = agora(),
                    minha = true,
                    entregue = saiu
                )
            )

            Toast.makeText(
                this,
                when {
                    longaDemais -> "Mensagem longa demais (máx ${BleMesh.MAX_TEXT} bytes) — guardei só aqui"
                    saiu -> "Mensagem enviada"
                    privadoPara != null -> "Sem rota para esse vizinho — guardei só aqui"
                    else -> "Nenhum vizinho com sinal agora — guardei só aqui"
                },
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    /** 16 chars hex minusculos: o peerID que vai no unicast. */
    private val PEER_ID = Regex("[0-9a-f]{16}")

    /**
     * A Agenda ja soltou o mesh no onStop antes desta tela abrir: sem religar aqui o
     * [BleMesh.enviar] do botao sai com o mesh morto. [BleMesh.adquirir]/[BleMesh.liberar]
     * sao o par 1:1 desta tela com o mesh — o `onStart` da Agenda chega antes do `onStop`
     * dela, entao quem decide o desligamento e o refcount, nao a ordem dos callbacks.
     */
    override fun onStart() {
        super.onStart()
        IdentidadeStore.iniciar(this)
        BleMesh.adquirir(this)
    }

    override fun onStop() {
        BleMesh.liberar()
        super.onStop()
    }

    private fun agora(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}