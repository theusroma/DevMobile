package com.example.aerotalks

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Portao de permissao e de liga/desliga do Bluetooth.
 *
 * Regras:
 * - **API 31+**: pede `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` + `BLUETOOTH_ADVERTISE` **juntos**,
 *   num request so. Localizacao **nao** e pedida (o manifesto marca SCAN/ADVERTISE com
 *   `neverForLocation`).
 * - **API <= 30**: pede `ACCESS_FINE_LOCATION` — e o que o scan BLE exige. `BLUETOOTH` e
 *   `BLUETOOTH_ADMIN` sao de instalacao (manifesto, `maxSdkVersion=30`), nao runtime.
 * - BT desligado -> `ACTION_REQUEST_ENABLE`. Sem BLE no hardware -> toast e app read-only.
 *
 * `registerForActivityResult` so funciona antes do `onStart`: chame [preparar] no `onCreate`
 * da Activity. Sem ele o pedido de permissao nao tem como voltar e [iniciarBluetooth] cai no
 * caminho alternativo (receiver de `ACTION_STATE_CHANGED`).
 */
object BlePerms {

    private const val TAG = "AeroTalks"

    private const val P_SCAN = "android.permission.BLUETOOTH_SCAN"
    private const val P_CONECTAR = "android.permission.BLUETOOTH_CONNECT"
    private const val P_ANUNCIAR = "android.permission.BLUETOOTH_ADVERTISE"
    private const val P_LOCAL = "android.permission.ACCESS_FINE_LOCATION"

    private class Registro(val activity: ComponentActivity) {
        var launcherPermissao: ActivityResultLauncher<Array<String>>? = null
        var launcherBt: ActivityResultLauncher<Intent>? = null
        var aoConceder: ((Boolean) -> Unit)? = null
        var aoLigarBt: ((Boolean) -> Unit)? = null
    }

    private val registros = HashMap<ComponentActivity, Registro>()

    // ------------------------------------------------------------------ hardware

    /** Tem BLE utilizavel (feature presente **e** adapter disponivel)? */
    fun suportaBle(ctx: Context): Boolean {
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return false
        return adapterDe(ctx) != null
    }

    /** O BT esta ligado? `false` tambem quando falta `BLUETOOTH_CONNECT`. */
    fun bluetoothLigado(ctx: Context): Boolean {
        val ad = adapterDe(ctx) ?: return false
        return try {
            ad.isEnabled
        } catch (e: SecurityException) {
            Log.w(TAG, "sem permissao para ler o estado do BT: ${e.message}")
            false
        }
    }

    /** Aviso de "sem BLE": a UI mostra o toast e segue read-only. */
    fun avisoSemBle(): String = "Este aparelho nao tem Bluetooth LE: o AeroTalks vai rodar so local."

    // --------------------------------------------------------------- permissoes

    /** API 31+: as 3 permissoes de servico, num request so. */
    fun permissoesServico(): Array<String> = arrayOf(P_SCAN, P_CONECTAR, P_ANUNCIAR)

    /** Ate o API 30 a unica permissao runtime do mesh e localizacao fina (exigida pelo scan). */
    fun permissaoLegada(): Array<String> = arrayOf(P_LOCAL)

    /** O conjunto que vale nesta API. */
    fun permissoesDaApi(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) permissoesServico() else permissaoLegada()

    fun concedida(ctx: Context, permissao: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permissao) == PackageManager.PERMISSION_GRANTED

    /** Todas as permissoes de servico desta API ja concedidas? */
    fun temTodas(ctx: Context): Boolean = permissoesDaApi().all { concedida(ctx, it) }

    /** A primeira permissao faltando, ou `null`. */
    fun permissaoFaltando(ctx: Context): String? = permissoesDaApi().firstOrNull { !concedida(ctx, it) }

    /**
     * Registra os launchers de resultado. **Chame no `onCreate`** — depois do `onStart` o
     * `registerForActivityResult` lanca e o pedido de permissao fica sem retorno.
     */
    fun preparar(activity: ComponentActivity) {
        if (registros.containsKey(activity)) return
        val r = Registro(activity)
        registros[activity] = r
        try {
            r.launcherPermissao = activity.registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { resultado ->
                val ok = resultado.values.all { it } || temTodas(activity)
                r.aoConceder?.invoke(ok)
            }
            r.launcherBt = activity.registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { res ->
                r.aoLigarBt?.invoke(bluetoothLigado(activity))
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "preparar fora do onCreate: ${e.message}")
        }
    }

    /** Resultado do pedido de permissao (chame antes de [pedirPermissaoEServicos]). */
    fun aoConcederPermissao(activity: ComponentActivity, cb: (Boolean) -> Unit) {
        preparar(activity)
        registros[activity]?.aoConceder = cb
    }

    /** Resultado do pedido de "ligar o Bluetooth" (caminho com `registerForActivityResult`). */
    fun aoLigarBluetooth(activity: ComponentActivity, cb: (Boolean) -> Unit) {
        preparar(activity)
        registros[activity]?.aoLigarBt = cb
    }

    /** Esquece a Activity (chame no `onDestroy`). */
    fun liberar(activity: ComponentActivity) {
        registros.remove(activity)
    }

    /**
     * Pede as permissoes de servico do mesh ([permissoesDaApi]).
     *
     * Devolve `true` quando ja estavam todas concedidas (nada a pedir) e `false` quando
     * dispara o pedido — nesse caso o veredito chega em [aoConcederPermissao] (ou numa
     * segunda chamada, tipicamente no `onResume`).
     */
    fun pedirPermissaoEServicos(activity: ComponentActivity): Boolean {
        preparar(activity)
        if (temTodas(activity)) {
            registros[activity]?.aoConceder?.invoke(true)
            return true
        }
        val r = registros[activity] ?: return false
        val launcher = r.launcherPermissao
        if (launcher == null) {
            // Activity ja foi iniciada: nao ha como pedir permissao agora.
            Log.w(TAG, "permissao nao pedida: chame BlePerms.preparar(this) no onCreate")
            r.aoConceder?.invoke(false)
            return false
        }
        launcher.launch(permissoesDaApi())
        return false
    }

    // ---------------------------------------------------------------------- BT

    /**
     * Liga o Bluetooth. [onOk] com o BT ligado, [onFail] se o usuario recusou.
     * Se ja estiver ligado, so [onOk].
     */
    fun iniciarBluetooth(activity: ComponentActivity, onOk: () -> Unit, onFail: () -> Unit) {
        if (bluetoothLigado(activity)) {
            onOk()
            return
        }
        preparar(activity)
        val r = registros[activity]
        val launcher = r?.launcherBt
        if (launcher != null) {
            r.aoLigarBt = { ok -> if (ok) onOk() else onFail() }
            try {
                launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                return
            } catch (e: Exception) {
                Log.w(TAG, "ACTION_REQUEST_ENABLE falhou: ${e.message}")
            }
        }
        pedirBluetoothPorReceiver(activity, onOk, onFail)
    }

    // ------------------------------------------------------------------ interno

    /**
     * Caminho sem `ActivityResult`: abre o dialogo do sistema e escuta
     * `ACTION_STATE_CHANGED`. Serve quando a Activity ja foi iniciada (o launcher nao pôde
     * ser registrado) — o receiver nao tem essa restricao.
     */
    private fun pedirBluetoothPorReceiver(ctx: Context, onOk: () -> Unit, onFail: () -> Unit) {
        var receiver: BroadcastReceiver? = null
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                try {
                    ctx.unregisterReceiver(receiver)
                } catch (e: IllegalArgumentException) {
                    // ja removido: nada a fazer
                }
                if (bluetoothLigado(ctx)) onOk() else onFail()
            }
        }
        try {
            ContextCompat.registerReceiver(
                ctx, receiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            ctx.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: Exception) {
            Log.w(TAG, "nao deu para pedir o Bluetooth: ${e.message}")
            try {
                ctx.unregisterReceiver(receiver)
            } catch (e2: IllegalArgumentException) {
                // nada a fazer
            }
            onFail()
        }
    }

    private fun adapterDe(ctx: Context): BluetoothAdapter? {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
        return try {
            bm.adapter
        } catch (e: SecurityException) {
            Log.w(TAG, "sem permissao para pegar o adapter: ${e.message}")
            null
        }
    }
}