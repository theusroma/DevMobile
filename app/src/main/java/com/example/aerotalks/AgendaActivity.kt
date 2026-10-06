package com.example.aerotalks

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class AgendaActivity : AppCompatActivity() {

    private lateinit var rvEventos: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agenda)

        rvEventos = findViewById(R.id.rvEventos)
        val btnNovoEvento = findViewById<Button>(R.id.btnNovoEvento)
        val btnVoltar = findViewById<Button>(R.id.btnVoltar)

        btnNovoEvento.setOnClickListener {
            startActivity(Intent(this, CadastroEventoActivity::class.java))
        }

        btnVoltar.setOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        val banco = BancoHelper(this)
        val eventos = banco.listar()
        rvEventos.layoutManager = LinearLayoutManager(this)
        rvEventos.adapter = EventoAdapter(eventos) { evento ->
            // Detalhes do evento
        }
    }
}