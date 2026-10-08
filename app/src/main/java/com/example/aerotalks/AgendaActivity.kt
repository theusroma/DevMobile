package com.example.aerotalks

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.random.Random

/** A sala de chat do mesh: cabeçalho, lista viva e botões. */
class AgendaActivity : AppCompatActivity() {

    private lateinit var store: MeshStore
    private lateinit var adapter: MensagemAdapter
    private lateinit var rvEventos: RecyclerView
    private lateinit var tvHeaderCanal: TextView
    private lateinit var tvStatus: TextView
    private lateinit var handler: Handler

    private var canalAtual = "geral"
    private var rota = 1

    /** A cada ~4s chega mensagem nova (ou não) e o mesh muda de figura. */
    private val tick = object : Runnable {
        override fun run() {
            rota = Random.nextInt(1, 4)
            atualizarCabecalho()

            MeshSimulator.proxima()?.let { msg ->
                store.inserir(msg)
                adapter.adicionar(msg)
                rvEventos.scrollToPosition(adapter.itemCount - 1)
            }

            handler.postDelayed(this, 4000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agenda)

        store = MeshStore(this)
        handler = Handler(Looper.getMainLooper())

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

        // primeira vez: semeia a conversa
        if (store.listar().isEmpty()) {
            MeshSimulator.semear().forEach { store.inserir(it) }
        }
        adapter.carregar(store.listar())
        rvEventos.scrollToPosition(adapter.itemCount - 1)

        btnRede.setOnClickListener {
            AndroidChatDialogs.rede(this, MeshSimulator.peers(), MeshSimulator.canais())
        }
        btnInfo.setOnClickListener { AndroidChatDialogs.about(this) }
        btnNovoEvento.setOnClickListener {
            val intent = Intent(this, CadastroEventoActivity::class.java)
            intent.putExtra("canal", canalAtual)
            startActivity(intent)
        }
        btnVoltar.setOnClickListener { finish() }

        atualizarCabecalho()
        handler.post(tick)
    }

    override fun onResume() {
        super.onResume()
        // pode ter mensagem nova vinda da tela de escrita
        recarregar()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun recarregar() {
        val todas = store.listar()
        if (todas.size > adapter.itemCount) {
            adapter.carregar(todas)
            rvEventos.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun atualizarCabecalho() {
        val conectados = MeshSimulator.peers().count { it.conectado }

        tvHeaderCanal.text = "#$canalAtual · / ${MeshSimulator.eu}"
        tvStatus.text = "CONECTADOS $conectados · ROTA $rota SALTOS"
    }
}