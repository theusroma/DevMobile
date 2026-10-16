package com.example.aerotalks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.InputFilter
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Conversa local + mesh BLE. Ciclo de vida: [BleMesh.adquirir] no `onStart` e
 * `BleMesh.liberar` no `onStop`, com o `BroadcastReceiver` da [PeerTable] registrado
 * e desregistrado no mesmo ciclo.
 *
 * O envio grava sempre no [MeshStore] e manda no fio quando ha rota.
 */
class AgendaActivity : AppCompatActivity() {

    private lateinit var store: MeshStore
    private lateinit var adapter: MensagemAdapter
    private lateinit var rvEventos: RecyclerView
    private lateinit var tvHeaderCanal: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvVazio: TextView
    private lateinit var chipCanal: Button
    private lateinit var chipPrivado: Button
    private lateinit var etMensagem: EditText

    private var canalAtual = "geral"
    private var privadoPara: String? = null
    private var privadoNome: String? = null
    private var receiverRegistrado = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PeerTable.ACTION_PEERS_CHANGED -> atualizarCabecalho()
                // A mensagem ja foi gravada pelo BleMesh: aqui so entra na lista.
                PeerTable.ACTION_MENSAGEM -> recarregar(rolar = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agenda)

        // Insets do sistema no root, para o rodape nao ficar atras do IME.
        findViewById<ViewGroup>(android.R.id.content).getChildAt(0).aplicarInsetsDoSistema()

        store = MeshStore(this)

        tvHeaderCanal = findViewById(R.id.tvHeaderCanal)
        tvStatus = findViewById(R.id.tvStatus)
        rvEventos = findViewById(R.id.rvEventos)
        tvVazio = findViewById(R.id.tvVazio)
        chipCanal = findViewById(R.id.chipCanal)
        chipPrivado = findViewById(R.id.chipPrivado)
        etMensagem = findViewById(R.id.etMensagem)

        val btnRede = findViewById<Button>(R.id.btnRede)
        val btnInfo = findViewById<Button>(R.id.btnInfo)
        val btnEnviar = findViewById<Button>(R.id.btnEnviar)

        adapter = MensagemAdapter()
        rvEventos.layoutManager = LinearLayoutManager(this)
        rvEventos.adapter = adapter

        // Teto de caracteres na UI; a checagem dura e de BYTES UTF-8 (no enviar()).
        etMensagem.filters = arrayOf(InputFilter.LengthFilter(BleMesh.MAX_TEXT))

        btnRede.setOnClickListener {
            AndroidChatDialogs.rede(this, PeerTable.peers())
        }
        btnInfo.setOnClickListener { AndroidChatDialogs.about(this) }
        btnEnviar.setOnClickListener { enviar() }

        // Enter no teclado tambem envia: cobre IME_ACTION_SEND e KEYCODE_ENTER.
        etMensagem.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                enviar()
                true
            } else {
                false
            }
        }
        etMensagem.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN &&
                !event.isShiftPressed
            ) {
                enviar()
                true
            } else {
                false
            }
        }

        chipCanal.setOnClickListener {
            AndroidChatDialogs.canal(this, canalAtual) { novo ->
                canalAtual = novo
                atualizarChips()
                atualizarCabecalho()
            }
        }
        chipPrivado.setOnClickListener {
            AndroidChatDialogs.privado(this, PeerTable.peers()) { peer ->
                privadoPara = peer?.id
                privadoNome = peer?.nickname
                atualizarChips()
            }
        }

        atualizarChips()
        recarregar(rolar = true)
    }

    override fun onStart() {
        super.onStart()
        IdentidadeStore.iniciar(this)
        BleMesh.adquirir(this)

        if (!receiverRegistrado) {
            val filtro = IntentFilter().apply {
                addAction(PeerTable.ACTION_PEERS_CHANGED)
                addAction(PeerTable.ACTION_MENSAGEM)
            }
            ContextCompat.registerReceiver(
                this,
                receiver,
                filtro,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistrado = true
        }
        atualizarCabecalho()
    }

    override fun onStop() {
        // Desregistra antes de derrubar o mesh.
        if (receiverRegistrado) {
            try {
                unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // ja removido pelo sistema: nada a fazer
            }
            receiverRegistrado = false
        }
        BleMesh.liberar()
        super.onStop()
    }

    override fun onDestroy() {
        AndroidChatDialogs.fechar()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        recarregar()
    }

    private fun recarregar(rolar: Boolean = false) {
        val todas = store.listar()
        adapter.carregar(todas)
        // Sem filtro por canal: listar() traz tudo; vazio = lista vazia.
        tvVazio.visibility = if (todas.isEmpty()) View.VISIBLE else View.GONE
        if (rolar && todas.isNotEmpty()) {
            rvEventos.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun atualizarCabecalho() {
        val conectados = PeerTable.peers().count { it.conectado }
        val mtu = BleMesh.mtu()

        tvHeaderCanal.text = "#$canalAtual · ${IdentidadeStore.apelido}"
        tvStatus.text = "BLE · CONECTADOS $conectados · MTU $mtu"
    }

    /** Reflete o estado dos chips: canal e alvo privado. */
    private fun atualizarChips() {
        chipCanal.text = "#$canalAtual ▾"
        chipPrivado.text =
            if (privadoPara != null) "Privado: $privadoNome ▾" else "Privado: ninguém ▾"
    }

    /** Grava local e manda no fio quando ha rota. */
    private fun enviar() {
        val texto = etMensagem.text.toString().trim()
        if (texto.isEmpty()) return

        // Checagem em BYTES UTF-8: acentos/emoji podem passar de 140 bytes.
        val bytes = texto.toByteArray(Charsets.UTF_8).size
        val longaDemais = bytes > BleMesh.MAX_TEXT

        // No fio vai SO o texto: canal e privado continuam locais.
        val saiu = !longaDemais && BleMesh.enviar(texto, privadoPara)

        // Grava sempre, mesmo sem rota ou sinal.
        MeshStore(this).inserir(
            Mensagem(
                autor = IdentidadeStore.apelido,
                conteudo = texto,
                canal = canalAtual,
                privadoPara = privadoPara,
                hora = agora(),
                minha = true,
                entregue = saiu
            )
        )

        Toast.makeText(
            this,
            when {
                longaDemais -> "Mensagem longa demais (máx ${BleMesh.MAX_TEXT} bytes) — salva apenas neste aparelho"
                saiu -> "Mensagem enviada"
                privadoPara != null -> "Sem rota para esse vizinho — salva apenas neste aparelho"
                else -> "Nenhum vizinho com sinal agora — salva apenas neste aparelho"
            },
            Toast.LENGTH_LONG
        ).show()

        etMensagem.setText("")
        recarregar(rolar = true)

        // O alvo privado vale para uma mensagem: volta para "ninguem".
        privadoPara = null
        privadoNome = null
        atualizarChips()
    }

    private fun agora(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}
