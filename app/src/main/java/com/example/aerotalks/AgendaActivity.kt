package com.example.aerotalks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.InputFilter
import android.view.KeyEvent
import android.view.View
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
 * Conversa local + mesh BLE de verdade (design §4/§5).
 *
 * Ciclo: `BleMesh.adquirir` no `onStart`, `BleMesh.liberar` no `onStop` (refcount: o
 * `onStart` da tela que entra chega antes do `onStop` desta), e o
 * `BroadcastReceiver` das duas acoes da [PeerTable] registrado **e desregistrado**
 * no mesmo ciclo — receiver vivo depois do `parar()` reagiria a um mesh desligado.
 *
 * O envio veio da antiga tela de cadastro de evento: campo + chips (#canal / privado)
 * na propria tela, grava sempre no [MeshStore] e manda no fio quando ha rota.
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
                // A mensagem ja foi gravada no MeshStore pelo BleMesh: aqui e so
                // trazer para a lista.
                PeerTable.ACTION_MENSAGEM -> recarregar(rolar = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agenda)

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

        // Enter no teclado tambem envia (SPEC §5.3): o campo e multiline, entao ora o
        // IME manda a acao, ora um KEYCODE_ENTER — cobrimos os dois. Depois de enviar o
        // campo esvazia, entao um disparo duplo do mesmo teclado cai no guard de vazio.
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
        // Desregistra ANTES de derrubar o mesh: assim nao chega evento de um mesh parado.
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
        // Sem filtro por canal (SPEC §5.2): listar() traz tudo, e o vazio e so a lista vazia.
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

    /** Reflete o estado dos chips (SPEC §5.4): canal e alvo privado. */
    private fun atualizarChips() {
        chipCanal.text = "#$canalAtual ▾"
        chipPrivado.text =
            if (privadoPara != null) "Privado: $privadoNome ▾" else "Privado: ninguém ▾"
    }

    /** Grava sempre local e manda no fio quando ha rota (SPEC §5.3). */
    private fun enviar() {
        val texto = etMensagem.text.toString().trim()
        if (texto.isEmpty()) return

        // Checagem dura de bytes: 140 caracteres podem passar do limite de 140 bytes
        // em acentos/emoji, e o BleMesh recusa sem truncar.
        val bytes = texto.toByteArray(Charsets.UTF_8).size
        val longaDemais = bytes > BleMesh.MAX_TEXT

        // No fio vai SO o texto: canal e privado continuam locais.
        val saiu = !longaDemais && BleMesh.enviar(texto, privadoPara)

        // Grava SEMPRE (mesmo sem rota/sem sinal): a transcricao local e a fonte da lista.
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
                longaDemais -> "Mensagem longa demais (máx ${BleMesh.MAX_TEXT} bytes) — guardei só aqui"
                saiu -> "Mensagem enviada"
                privadoPara != null -> "Sem rota para esse vizinho — guardei só aqui"
                else -> "Nenhum vizinho com sinal agora — guardei só aqui"
            },
            Toast.LENGTH_LONG
        ).show()

        etMensagem.setText("")
        recarregar(rolar = true)

        // O alvo privado vale para uma mensagem so (SPEC §5.3): volta para "ninguem".
        privadoPara = null
        privadoNome = null
        atualizarChips()
    }

    private fun agora(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}
