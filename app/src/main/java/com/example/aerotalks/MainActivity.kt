package com.example.aerotalks

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Portao do app: BLE → permissao → Bluetooth ligado → apelido → Agenda.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Insets do sistema no root.
        findViewById<ViewGroup>(android.R.id.content).getChildAt(0).aplicarInsetsDoSistema()

        // O launcher de permissao precisa estar registrado antes do onStart.
        BlePerms.preparar(this)
        IdentidadeStore.iniciar(this)

        val btnComecar = findViewById<Button>(R.id.btnComecar)
        val btnSobre = findViewById<Button>(R.id.btnSobre)

        btnComecar.setOnClickListener { comecar() }
        btnSobre.setOnClickListener { AndroidChatDialogs.about(this) }
    }

    override fun onDestroy() {
        // Fecha os dialogs abertos.
        AndroidChatDialogs.fechar()
        BlePerms.liberar(this)
        super.onDestroy()
    }

    private fun comecar() {
        if (!BlePerms.suportaBle(this)) {
            // Sem BLE: avisa e segue read-only.
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
                // Mesmo caminho do sem-BLE: aviso e segue para a Agenda.
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

    /**
     * Abre a tela Apelido: todos os caminhos do portao desembocam aqui.
     */
    private fun abrirAgenda() {
        IdentidadeStore.iniciar(this)
        startActivity(Intent(this, ApelidoActivity::class.java))
    }
}