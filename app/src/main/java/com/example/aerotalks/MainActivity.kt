package com.example.aerotalks

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Portao do app: BLE → permissao → Bluetooth ligado → apelido → Agenda (design §5).
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // targetSdk 35+: edge-to-edge imposto — aplica os insets no root (SPEC §5.1).
        findViewById<ViewGroup>(android.R.id.content).getChildAt(0).aplicarInsetsDoSistema()

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

    /**
     * Todos os caminhos do portao desembocam aqui: em vez do AlertDialog de apelido,
     * agora abre a tela Apelido (SPEC §3/§4). Nada de estado local: a tela sempre aparece.
     */
    private fun abrirAgenda() {
        IdentidadeStore.iniciar(this)
        startActivity(Intent(this, ApelidoActivity::class.java))
    }
}