package com.example.aerotalks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Conversa local + mesh BLE de verdade (design §4/§5).
 *
 * Ciclo: `BleMesh.adquirir` no `onStart`, `BleMesh.liberar` no `onStop` (refcount: o
 * `onStart` da tela que entra chega antes do `onStop` desta), e o
 * `BroadcastReceiver` das duas acoes da [PeerTable] registrado **e desregistrado**
 * no mesmo ciclo — receiver vivo depois do `parar()` reagiria a um mesh desligado.
 */
class AgendaActivity : AppCompatActivity() {

    private lateinit var store: MeshStore
    private lateinit var adapter: MensagemAdapter
    private lateinit var rvEventos: RecyclerView
    private lateinit var tvHeaderCanal: TextView
    private lateinit var tvStatus: TextView

    private var canalAtual = "geral"
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

        tvHeaderCanal = findViewById<TextView>(R.id.tvHeaderCanal)
        tvStatus = findViewById<TextView>(R.id.tvStatus)
        rvEventos = findViewById<RecyclerView>(R.id.rvEventos)

        val btnRede = findViewById<Button>(R.id.btnRede)
        val btnInfo = findViewById<Button>(R.id.btnInfo)
        val btnNovoEvento = findViewById<Button>(R.id.btnNovoEvento)
        val btnVoltar = findViewById<Button>(R.id.btnVoltar)

        adapter = MensagemAdapter()
        rvEventos.layoutManager = LinearLayoutManager(this)
        rvEventos.adapter = adapter

        btnRede.setOnClickListener {
            AndroidChatDialogs.rede(this, PeerTable.peers())
        }
        btnInfo.setOnClickListener { AndroidChatDialogs.about(this) }
        btnNovoEvento.setOnClickListener {
            val intent = Intent(this, CadastroEventoActivity::class.java)
            intent.putExtra("canal", canalAtual)
            startActivity(intent)
        }
        btnVoltar.setOnClickListener { finish() }

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
}