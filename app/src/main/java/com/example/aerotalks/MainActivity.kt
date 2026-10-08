package com.example.aerotalks

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnComecar = findViewById<Button>(R.id.btnComecar)
        val btnSobre = findViewById<Button>(R.id.btnSobre)

        btnComecar.setOnClickListener {
            Toast.makeText(this, "inicializando o mesh...", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, AgendaActivity::class.java))
        }

        btnSobre.setOnClickListener {
            AndroidChatDialogs.about(this)
        }
    }
}