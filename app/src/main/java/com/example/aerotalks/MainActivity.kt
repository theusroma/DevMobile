package com.example.aerotalks

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Portao do app: BLE → permissao → Bluetooth ligado → apelido → Agenda (design §5).
 */
class MainActivity : AppCompatActivity() {

    /** O apelido ja foi confirmado nesta sessao? (1o uso mostra o dialog) */
    private var apelidoConfirmado = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // registerForActivityResult so vale ANTES do onStart: o portao de permissao
        // precisa do launcher pronto aqui, senao o pedido nao tem retorno.
        BlePerms.preparar(this)
        IdentidadeStore.iniciar(this)

        val btnComecar = findViewById<Button>(R.id.btnComecar)
        val btnSobre = findViewById<Button>(R.id.btnSobre)

        btnComecar.setOnClickListener { comecar() }
        btnSobre.setOnClickListener { AndroidChatDialogs.about(this) }
    }

    override fun onDestroy() {
        // A janela do dialogo de SOBRE vive num campo estatico: sem isto ela vaza
        // na rotacao (WindowLeaked).
        AndroidChatDialogs.fechar()
        BlePerms.liberar(this)
        super.onDestroy()
    }

    private fun comecar() {
        if (!BlePerms.suportaBle(this)) {
            // Sem BLE: avisa e segue — o app funciona so local (read-only).
            Toast.makeText(this, BlePerms.avisoSemBle(), Toast.LENGTH_LONG).show()
            abrirAgenda()
            return
        }

        if (BlePerms.temTodas(this)) {
            ligarBluetoothSePrecisar()
            return
        }

        BlePerms.aoConcederPermissao(this) { concedida ->
            if (concedida) {
                ligarBluetoothSePrecisar()
            } else {
                Toast.makeText(
                    this,
                    "Sem permissão de Bluetooth o AeroTalks roda só local.",
                    Toast.LENGTH_LONG
                ).show()
                // Duas negativas nao podem trancar o usuario fora do app: mesmo caminho
                // do sem-BLE — aviso e segue para a Agenda (que roda read-only).
                abrirAgenda()
            }
        }
        BlePerms.pedirPermissaoEServicos(this)
    }

    private fun ligarBluetoothSePrecisar() {
        BlePerms.iniciarBluetooth(
            this,
            onOk = { abrirAgenda() },
            onFail = {
                Toast.makeText(
                    this,
                    "Ligue o Bluetooth para o mesh funcionar.",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    private fun abrirAgenda() {
        IdentidadeStore.iniciar(this)
        if (apelidoConfirmado) {
            irParaAgenda()
            return
        }
        val campo = EditText(this).apply {
            setText(IdentidadeStore.apelido)
            hint = "Seu apelido no mesh"
            maxLines = 1
        }
        AndroidChatDialogs.mostrar(
            AlertDialog.Builder(this)
                .setTitle("Como você quer ser chamado?")
                .setMessage("Esse nome vai para quem estiver por perto no mesh.")
                .setView(campo)
                .setCancelable(false)
                .setPositiveButton("ENTRAR") { _, _ ->
                    IdentidadeStore.setApelido(campo.text.toString())
                    apelidoConfirmado = true
                    irParaAgenda()
                }
                .show()
        )
    }

    private fun irParaAgenda() {
        startActivity(Intent(this, AgendaActivity::class.java))
    }
}