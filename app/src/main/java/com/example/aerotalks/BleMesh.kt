package com.example.aerotalks

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Controlador da malha BLE (design §4): advertiser + scanner + GATT client + GATT server.
 *
 * **Nao e Service** (FGS fora de escopo): objeto ligado ao ciclo de vida da Activity —
 * [iniciar] no `onStart`, [parar] no `onStop`.
 *
 * Dois papeis, um so wire (design §4.8):
 * - **Cliente** (eu tenho o peerID hex MAIOR): eu conecto e escrevo por `writeCharacteristic`;
 *   o outro lado responde por `notifyCharacteristicChanged`.
 * - **Servidor** (eu tenho o peerID hex MENOR): eu abro o server e NUNCA conecto; anuncio por
 *   notify, e o unicast vai enderecado ao device do peer.
 *
 * Iniciador deterministico (design §2): compara-se a string hex do peerID, nunca o MAC.
 * Sem esse gate os dois lados conectam um no outro (tempestade GATT_133).
 *
 * Permissao: TODO `gatt.*` e `device.address` passam por [comSeguranca] — em API 31+ a revogacao
 * de `BLUETOOTH_CONNECT` no meio da sessao crasha se a chamada nao for guardada.
 */
object BleMesh {

    private const val TAG = "AeroTalks"

    // --------------------------------------------------------------------- UUIDs

    /** Servico do mesh (UUID projeto-local). */
    val SERVICE_UUID: UUID = UUID.fromString("6d9f0001-9e4b-4c7e-8a3f-1b2c3d4e5f60")

    /** Caracteristica de payload: WRITE + WRITE_NO_RESPONSE + NOTIFY. */
    val CHAR_UUID: UUID = UUID.fromString("6d9f0002-9e4b-4c7e-8a3f-1b2c3d4e5f60")

    /** Client Characteristic Configuration Descriptor. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * 0xFD6F (16-bit) para advertising e filtro de scan.
     * **Nunca** UUID 128-bit nesses dois campos: 33-47 B estoura os 31 B do advertising e o
     * advertiser falha em silencio (design §4). O UUID de 128 abaixo e so a forma de
     * empacotar o valor de 16 bits; o que vai no ar sao 2 bytes.
     */
    const val SERVICE_DATA_16: Int = 0xFD6F
    private val SERVICE_UUID_16: ParcelUuid =
        ParcelUuid(UUID.fromString("0000fd6f-0000-1000-8000-00805f9b34fb"))

    // ---------------------------------------------------------------- constantes

    /** MTU pedido no cliente. */
    const val MTU_DESEJADO: Int = 517

    /** Abaixo disso o link e INCOMPATIVEL e nem chega a descobrir servico (design §4.6). */
    const val MTU_MINIMO: Int = 173

    /** 3 B de overhead ATT por write: `30 + len <= mtu - 3`. */
    const val MTU_OVERHEAD: Int = 3

    /** Teto de payload de MESSAGE — o mesmo do codec. */
    const val MAX_TEXT: Int = BleCodec.MAX_TEXT

    /** Tick de poda/expiracao (design §4.9). */
    const val TICK_MS: Long = 15_000L

    /** Backoff de reconexao 5/15/45 s (Risco 2). */
    private val BACKOFF_MS = longArrayOf(5_000L, 15_000L, 45_000L)

    /** LRU anti-duplicata: 64 pares (senderID, timestamp). */
    private const val DEDUPE_MAX = 64

    /** Janela entre o LEAVE e o fechamento do GATT no [parar]. */
    private const val JMS_ADEUS = 250L

    /** Canal local das mensagens recebidas (o fio e so texto; §2). */
    private const val CANAL_GERAL = "geral"

    // -------------------------------------------------------------------- estados

    const val ESTADO_CONECTANDO = "CONECTANDO"
    const val ESTADO_PRONTO = "PRONTO"
    const val ESTADO_INCOMPATIVEL = "INCOMPATIVEL"
    const val ESTADO_DESCONECTADO = "DESCONECTADO"

    /** Link como a UI ve (header "BLE · CONECTADOS n · MTU m"). */
    data class Conexao(
        val peerId: String,
        val address: String,
        val estado: String,
        val mtu: Int,
        val lastSeen: Long
    )

    /** Um link do papel CLIENTE (eu conecto no peer de id MAIOR). */
    private class Link(val peerId: String, val device: BluetoothDevice) {
        var gatt: BluetoothGatt? = null
        var char: BluetoothGattCharacteristic? = null
        var address: String = ""
        var estado: String = ESTADO_CONECTANDO
        var mtu: Int = 0
        var maxText: Int = 0
        var lastSeen: Long = System.currentTimeMillis()
        /** Fila de 1 write em voo por direcao (design §4.8c). */
        var emVoo: ByteArray? = null
        var reenvios: Int = 0
    }

    /** Um assinante do nosso GATT server (papel SERVIDOR). */
    private class Assinante(val device: BluetoothDevice) {
        var address: String = ""
        /** peerId so e conhecido depois do ANNOUNCE que o cliente escreve. */
        var peerId: String? = null
        var notificando: Boolean = false
        var anunciado: Boolean = false
        var lastSeen: Long = System.currentTimeMillis()
    }

    @Volatile
    private var appCtx: Context? = null

    private var manager: BluetoothManager? = null

    @Volatile
    private var adapter: BluetoothAdapter? = null

    @Volatile
    private var servidor: BluetoothGattServer? = null

    @Volatile
    private var servicoAberto = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) = onAnunciado(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onAnunciado(it) }
        }
    }

    private val announceCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertising falhou: $errorCode (falta BLUETOOTH_ADVERTISE?)")
        }
    }

    private val links = LinkedHashMap<String, Link>()
    private val assinantes = LinkedHashMap<String, Assinante>()
    private val conhecidos = LinkedHashMap<String, BluetoothDevice>()
    private val rssiPorId = HashMap<String, Int>()
    private val proximasTentativas = HashMap<String, Long>()

    private val handler = Handler(Looper.getMainLooper())
    private var store: MeshStore? = null

    @Volatile
    private var ativo = false

    /** Gera um so `onStart`/`onStop` ganha: o fechamento adiado checa se ainda vale. */
    private var geracao = 0

    /**
     * As callbacks de BLE chegam em thread de binder e o tick roda na main: os mapas
     * compartilhados so podem ser tocados sob este monitor (sempre depois do do `object`,
     * nunca o inverso).
     */
    private val lockEstado = Any()

    /** Secao critica dos mapas do mesh (ver [lockEstado]). */
    private inline fun <T> estado(bloco: () -> T): T = synchronized(lockEstado, bloco)

    private val vistos = ArrayDeque<String>()
    private val vistosSet = HashSet<String>()

    private val tick = object : Runnable {
        override fun run() {
            varrer()
            handler.postDelayed(this, TICK_MS)
        }
    }

    // ------------------------------------------------------------ ciclo de vida

    /**
     * Liga o mesh: identidade, GATT server, advertising e scan. Idempotente.
     *
     * Sem BLE no hardware (ou com o BT desligado) nao lanca: fica inativo e o app segue
     * read-only — quem avisa e quem liga o BT e o [BlePerms].
     */
    @Synchronized
    fun iniciar(ctx: Context) {
        if (ativo) return
        val app = ctx.applicationContext
        // Antes de qualquer early-return: sem isto a identidade nunca liga e a lane C
        // estoura IllegalStateException quando nao ha BLE/adapter/BT desligado.
        IdentidadeStore.iniciar(app)
        PeerTable.iniciar(app)
        appCtx = app // antes de qualquer uso (meuId() em seguida materializa o secret)
        val bm = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (bm == null || !app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.w(TAG, "BLE indisponivel: app segue read-only")
            return
        }
        val ad = comSeguranca("bluetoothManager.adapter") { bm.adapter }
        if (ad == null) {
            Log.w(TAG, "adapter de BLE inacessivel (falta BLUETOOTH_CONNECT?)")
            return
        }
        if (comSeguranca("adapter.isEnabled") { ad.isEnabled } != true) {
            Log.w(TAG, "Bluetooth desligado: quem liga e o BlePerms.iniciarBluetooth")
            return
        }

        meuId() // materializa o secret antes de anunciar

        // Reinicio rapido (onStop->onStart sem o [parar] no meio): derruba o que sobrou
        // ANTES de abrir outro GATT server, senao o Android invalida o callback antigo.
        // Precisa vir antes de `adapter = ad`: [fecharTudo] para scan/advertising com o
        // adapter velho e depois zera adapter/manager.
        val sobrou = estado { links.isNotEmpty() || assinantes.isNotEmpty() || conhecidos.isNotEmpty() }
        if (sobrou) fecharTudo()

        manager = bm
        adapter = ad
        store = MeshStore(app)
        geracao++

        abrirServico()
        startAdvertising()
        startScan()
        ativo = true
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
        Log.i(TAG, "mesh iniciada: ${IdentidadeStore.peerId}")
    }

    /**
     * Desliga tudo: LEAVE best-effort, para advertising/scan, fecha server e clientes e
     * marca os peers desconectados. Idempotente.
     */
    @Synchronized
    fun parar() {
        if (!ativo && appCtx == null) return
        handler.removeCallbacksAndMessages(null)
        mandarLeave()
        val minhaGeracao = geracao
        ativo = false
        appCtx = null
        store = null
        // Da tempo do LEAVE sair antes de derrubar o link.
        handler.postDelayed({ if (minhaGeracao == geracao) fecharTudo() }, JMS_ADEUS)
        Log.i(TAG, "mesh parada")
    }

    /**
     * Quantas telas estao usando o mesh ([adquirir] sem [liberar] correspondente).
     *
     * O ciclo real do Android NAO e 1:1: ao trocar de Activity a ordem e
     * `A.onPause -> B.onStart -> A.onStop`, ou seja, o `onStop` da tela que sai chega
     * DEPOIS do `onStart` da que entra. Parar/iniciar direto por Activity matava a malha
     * por baixo da tela de escrita. Com refcount o mesh so morre no ultimo [liberar].
     */
    private var donos = 0

    /** Registra uma tela usuaria e garante o mesh ligado (idempotente em [iniciar]). */
    @Synchronized
    fun adquirir(ctx: Context) {
        donos++
        iniciar(ctx)
    }

    /** Devolve o uso de uma tela; so desliga o mesh quando ninguem mais segura. */
    @Synchronized
    fun liberar() {
        donos = (donos - 1).coerceAtLeast(0)
        if (donos == 0) parar()
    }

    /** Mesh usavel? Exige BLE ligado e pelo menos um canal aberto. */
    fun pronto(): Boolean = estado {
        ativo && (servicoAberto || links.values.any { it.estado == ESTADO_PRONTO })
    }

    /** Snapshot dos links de cliente, para o header da UI. */
    fun conexoes(): List<Conexao> = estado {
        links.values.map { Conexao(it.peerId, it.address, it.estado, it.mtu, it.lastSeen) }
    }

    /** O GATT server esta no ar? (o outro lado ainda pode conectar em nos) */
    fun servicoAberto(): Boolean = servicoAberto

    /** MTU do primeiro link PRONTO, ou 0. */
    fun mtu(): Int = estado { links.values.firstOrNull { it.estado == ESTADO_PRONTO }?.mtu ?: 0 }

    /** Quantos links de cliente estao PRONTO. */
    fun prontos(): Int = estado { links.values.count { it.estado == ESTADO_PRONTO } }

    // ----------------------------------------------------------------- envio (R2)

    /**
     * Envia [texto]. [destino] `null` (ou o hex de BROADCAST) = todos os canais abertos.
     *
     * **R2 (registrado):** com [destino] preenchido so entrega se o link daquele peer estiver
     * PRONTO; caso contrario devolve `false` e o chamador mostra "sem rota". Sem relay: nada
     * sai por um terceiro.
     *
     * Devolve `true` se o frame entrou em ao menos um caminho (write do cliente ou notify do
     * servidor). Texto acima de [MAX_TEXT] bytes UTF-8 devolve `false` — nunca trunca em
     * silencio (design §4).
     */
    fun enviar(texto: String, destino: String?): Boolean {
        if (!pronto()) return false
        val meu = meuId() ?: return false
        val payload = texto.toByteArray(Charsets.UTF_8)
        if (payload.isEmpty()) return false
        if (payload.size > MAX_TEXT) {
            Log.w(TAG, "texto de ${payload.size} B acima de $MAX_TEXT: recusado (sem truncar)")
            return false
        }

        val alvo = destino?.trim()?.lowercase()
        val hexBroadcast = BleCodec.hex(BleCodec.BROADCAST)
        val unicast = alvo != null && alvo.isNotEmpty() && alvo != hexBroadcast
        val destBytes = if (unicast) BleCodec.deHexId(alvo) ?: return false else BleCodec.BROADCAST

        val frame = BleCodec.encode(BleCodec.MESSAGE, meuId8(meu), destBytes, payload)
        var aceito = false

        estado {
            // Caminho do CLIENTE: um write por link PRONTO endereçado ao peer do unicast.
            for (link in links.values) {
                if (link.estado != ESTADO_PRONTO) continue
                if (unicast && link.peerId != alvo) continue
                if (frame.size > link.mtu - MTU_OVERHEAD) continue // gate de MTU do link
                if (enfileirarWrite(link, frame)) aceito = true
            }

            // Caminho do SERVIDOR: unicast = notify no device do peer; broadcast = um por assinante.
            for (a in assinantes.values) {
                if (!a.notificando) continue
                if (unicast && (a.peerId == null || a.peerId != alvo)) continue
                if (notificar(a, frame)) aceito = true
            }
        }

        if (unicast && !aceito) Log.w(TAG, "sem rota para $alvo (R2)")
        return aceito
    }

    /** LEAVE best-effort para todo mundo (design §4.10). */
    private fun mandarLeave() {
        val meu = meuId() ?: return
        val frame = BleCodec.encode(BleCodec.LEAVE, meuId8(meu), BleCodec.ZEROS, ByteArray(0))
        estado {
            for (link in links.values) {
                if (link.estado == ESTADO_PRONTO) enfileirarWrite(link, frame)
            }
            for (a in assinantes.values) {
                if (a.notificando) notificar(a, frame)
            }
        }
    }

    // --------------------------------------------------- fila de write (papel CLIENTE)

    /** Fila de 1 operacao em voo por link: havendo write pendente, devolve `false`. */
    private fun enfileirarWrite(link: Link, frame: ByteArray?): Boolean {
        if (frame == null) return false
        return estado {
            if (link.gatt == null || link.char == null) return@estado false
            if (link.emVoo != null) return@estado false
            link.reenvios = 0
            escrever(link, frame)
        }
    }

    private fun escrever(link: Link, frame: ByteArray): Boolean = estado {
        val gatt = link.gatt ?: return@estado false
        val c = link.char ?: return@estado false
        val ok = comSeguranca("writeCharacteristic(${link.peerId})") {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            escreverRaw(gatt, c, frame)
        } ?: false
        link.emVoo = if (ok) frame else null
        ok
    }

    @Suppress("DEPRECATION")
    private fun escreverRaw(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, frame: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            // API 33+: o valor vai no proprio write e o RETORNO E O STATUS (nao Boolean).
            // WRITE_TYPE_DEFAULT e o mesmo que [escrever] ja deixa na characteristic: e ele que
            // mantem o eco em `onCharacteristicWrite`, de onde a fila de 1 write em voo tira o
            // acerto (design §4.8c). NO_RESPONSE nao ecoaria e o emVoo nunca voltaria a null.
            gatt.writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            c.value = frame
            gatt.writeCharacteristic(c)
        }

    // --------------------------------------------------- server (papel SERVIDOR)

    private fun abrirServico() {
        val ctx = appCtx ?: return
        val svc = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val c = BluetoothGattCharacteristic(
            CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        c.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        svc.addCharacteristic(c)

        val s = comSeguranca("openGattServer") { manager?.openGattServer(ctx, ServidorCallback()) }
        servidor = s
        servicoAberto = s != null
        if (s == null) {
            Log.w(TAG, "GATT server nao abriu")
            return
        }
        comSeguranca("addService") { s.addService(svc) }
    }

    private fun fecharServidor() {
        comSeguranca("clearServices") { servidor?.clearServices() }
        comSeguranca("close") { servidor?.close() }
        servidor = null
        servicoAberto = false
    }

    /** Um notify. `false` = fila do GATT cheia (e assim que a fila de 1 se revela). */
    private fun notificar(a: Assinante, frame: ByteArray?): Boolean {
        val f = frame ?: return false
        val s = servidor ?: return false
        val c = comSeguranca("getService(server)") { s.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID) }
            ?: return false
        val ok = comSeguranca("notifyCharacteristicChanged(${a.address})") {
            // Overload 4-arg (API 33+): o valor vai no proprio notify e o retorno e STATUS.
            // `false` continua significando fila do GATT cheia.
            s.notifyCharacteristicChanged(a.device, c, false, f) == BluetoothStatusCodes.SUCCESS
        } ?: false
        a.lastSeen = System.currentTimeMillis()
        return ok
    }

    // [ServidorCallback] e [ClienteCallback] sao `class` (nao `inner`): o [BleMesh] ja e um
    // object/singleton, entao todo o estado abaixo e alcancavel direto pelo nome do object.
    private class ServidorCallback : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val addr = device?.addressSeguro() ?: return
            estado {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        assinantes[addr] = Assinante(device).also { it.address = addr }
                        Log.i(TAG, "servidor: conectado $addr")
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        assinantes.remove(addr)
                        Log.i(TAG, "servidor: desconectado $addr")
                    }
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val d = device ?: return
            val addr = d.addressSeguro() ?: return
            val ok = descriptor != null &&
                descriptor.uuid == CCCD_UUID &&
                value != null &&
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            comSeguranca("sendResponse(descriptorWrite)") {
                servidor?.sendResponse(
                    d,
                    requestId,
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    offset,
                    value
                )
            }
            val a = estado { assinantes[addr] } ?: return
            if (!ok) return
            estado {
                a.notificando = true
                // Bifurcacao dos dois papeis (design §4.8b): o SERVIDOR anuncia por notify.
                anunciar(a) // se a fila estiver cheia, o proximo tick tenta de novo
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val addr = device?.addressSeguro()
            val payload = if (characteristic != null && characteristic.uuid == CHAR_UUID) value else null
            val serve = payload != null
            val d = device
            if (responseNeeded && d != null) {
                comSeguranca("sendResponse(characteristicWrite)") {
                    servidor?.sendResponse(
                        d,
                        requestId,
                        if (serve) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                        offset,
                        value
                    )
                }
            }
            val bytes = payload ?: return
            val meu = meuId() ?: return
            val agora = System.currentTimeMillis()
            // O ANNOUNCE do cliente e a primeira coisa escrita: e ele que liga peerId -> address,
            // sem o qual o unicast por notify nao tem destino.
            val env = BleCodec.decode(bytes)
            estado {
                addr?.let { assinantes[it]?.lastSeen = agora }
                if (env != null && env.tipo == BleCodec.ANNOUNCE && env.remetHex != meu) {
                    addr?.let { assinantes[it]?.peerId = env.remetHex }
                }
            }
            processar(bytes, addr)
        }
    }

    // --------------------------------------------------- cliente (papel CLIENTE)

    private fun conectar(peerId: String, device: BluetoothDevice, agora: Long) {
        val link = Link(peerId, device)
        link.address = device.addressSeguro() ?: return
        val ctx = appCtx ?: return
        estado {
            links[peerId] = link
            val gatt = comSeguranca("connectGatt(${link.address})") {
                // autoConnect=false e TRANSPORT_LE explicito; o overload 4-arg e o unico caminho
                // para forcar LE no 33+. `connectGatt` e do DEVICE (o adapter nao tem).
                @Suppress("DEPRECATION")
                device.connectGatt(ctx, false, ClienteCallback(link), BluetoothDevice.TRANSPORT_LE)
            }
            link.gatt = gatt
            if (gatt == null) {
                links.remove(peerId)
                Log.w(TAG, "connectGatt devolveu null para $peerId")
            }
            agendarTentativa(peerId, agora)
        }
    }

    private val tentativasPorId = HashMap<String, Int>()

    /** Backoff 5/15/45 s por peer (Risco 2). Zera quando o link chega a PRONTO. */
    private fun agendarTentativa(peerId: String, agora: Long) {
        val n = tentativasPorId[peerId] ?: 0
        proximasTentativas[peerId] = agora + BACKOFF_MS[n.coerceIn(0, BACKOFF_MS.size - 1)]
        if (n + 1 < BACKOFF_MS.size) tentativasPorId[peerId] = n + 1
    }

    private fun zerarTentativas(peerId: String) {
        tentativasPorId.remove(peerId)
        proximasTentativas.remove(peerId)
    }

    private class ClienteCallback(private val link: Link) : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    link.estado = ESTADO_CONECTANDO
                    link.lastSeen = System.currentTimeMillis()
                    comSeguranca("requestMtu") { gatt?.requestMtu(MTU_DESEJADO) }
                }
                BluetoothProfile.STATE_DISCONNECTED -> caiu(gatt, status)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            // GATE PRIMEIRO (design §4.6 / Risco 1): MTU curto deixa o link INCOMPATIVEL e nada
            // mais e computado — nem maxText, nem discoverServices.
            if (mtu < MTU_MINIMO) {
                link.mtu = mtu
                link.maxText = 0
                link.estado = ESTADO_INCOMPATIVEL
                Log.w(TAG, "MTU $mtu < $MTU_MINIMO com ${link.peerId}: link INCOMPATIVEL")
                return
            }
            link.mtu = mtu
            // 30 (cabecalho fixo) + 3 (overhead ATT) = 33 B de folga antes do texto.
            link.maxText = maxOf(0, minOf(MAX_TEXT, mtu - BleCodec.HEADER - MTU_OVERHEAD))
            comSeguranca("discoverServices") { gatt?.discoverServices() }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            val g = gatt ?: return
            val svc = comSeguranca("getService") { g.getService(SERVICE_UUID) } ?: return
            val c = comSeguranca("getCharacteristic") { svc.getCharacteristic(CHAR_UUID) } ?: return
            val cccd = comSeguranca("getDescriptor(CCCD)") { c.getDescriptor(CCCD_UUID) } ?: return
            link.char = c
            comSeguranca("setCharacteristicNotification") { g.setCharacteristicNotification(c, true) }
            comSeguranca("writeDescriptor(CCCD)") { escreverCccd(g, cccd, c) }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (descriptor?.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                caiu(gatt, status)
                return
            }
            estado {
                link.estado = ESTADO_PRONTO
                link.lastSeen = System.currentTimeMillis()
                zerarTentativas(link.peerId)
                Log.i(TAG, "link PRONTO com ${link.peerId} (mtu=${link.mtu}, maxText=${link.maxText})")
                // Bifurcacao dos dois papeis (design §4.8a): o CLIENTE anuncia por write.
                enfileirarWrite(link, frameAnounce())
            }
            PeerTable.tocar(link.peerId)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid != CHAR_UUID) return
            link.lastSeen = System.currentTimeMillis()
            processar(value, link.peerId)
        }

        @Deprecated("pre-33: abaixo do API 33 so existe esta assinatura")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            val c = characteristic ?: return
            @Suppress("DEPRECATION")
            val v = comSeguranca("characteristic.value") { c.value } ?: return
            if (c.uuid != CHAR_UUID) return
            link.lastSeen = System.currentTimeMillis()
            processar(v, link.peerId)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            estado {
                val frame = link.emVoo
                link.emVoo = null
                if (status != BluetoothGatt.GATT_SUCCESS && frame != null && link.reenvios < 1) {
                    link.reenvios++
                    Log.w(TAG, "write para ${link.peerId} status=$status; reenviando 1x")
                    escrever(link, frame)
                }
            }
        }
    }

    @Suppress("DEPRECATION", "UNUSED_PARAMETER")
    private fun escreverCccd(gatt: BluetoothGatt, cccd: BluetoothGattDescriptor, c: BluetoothGattCharacteristic) {
        if (Build.VERSION.SDK_INT >= 33) {
            // API 33+: valor no proprio write, retorno = status (0 = aceito).
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
        }
    }

    private fun caiu(gatt: BluetoothGatt?, status: Int) {
        linkDe(gatt)?.let { fecharLink(it, status) }
    }

    private fun linkDe(gatt: BluetoothGatt?): Link? =
        if (gatt == null) null else links.values.firstOrNull { it.gatt === gatt }

    private fun fecharLink(link: Link, status: Int?) {
        estado {
            link.estado = ESTADO_DESCONECTADO
            link.char = null
            link.emVoo = null
            if (status != null) {
                Log.w(TAG, "link caiu com ${link.peerId} (status=$status); tentativas ${tentativasPorId[link.peerId] ?: 0}")
            }
            comSeguranca("disconnect") { link.gatt?.disconnect() }
            comSeguranca("close") { link.gatt?.close() }
            link.gatt = null
            links.remove(link.peerId)
        }
    }

    // --------------------------------------------------- scan / advertising

    private fun leScanner() = comSeguranca("bluetoothLeScanner") { adapter?.bluetoothLeScanner }

    private fun startAdvertising() {
        val a = comSeguranca("bluetoothLeAdvertiser") { adapter?.bluetoothLeAdvertiser } ?: return
        val meu = meuId() ?: return
        val dados = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(SERVICE_UUID_16)
            .addServiceData(SERVICE_UUID_16, meuId8(meu)) // os 8 B do peerID
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        comSeguranca("startAdvertising") { a.startAdvertising(settings, dados, announceCallback) }
    }

    private fun pararAdvertising() {
        val a = comSeguranca("bluetoothLeAdvertiser") { adapter?.bluetoothLeAdvertiser } ?: return
        comSeguranca("stopAdvertising") { a.stopAdvertising(announceCallback) }
    }

    private fun startScan() {
        val s = leScanner() ?: return
        val filtro = ScanFilter.Builder().setServiceUuid(SERVICE_UUID_16).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        comSeguranca("startScan") { s.startScan(listOf(filtro), settings, scanCallback) }
    }

    private fun pararScan() {
        val s = leScanner() ?: return
        comSeguranca("stopScan") { s.stopScan(scanCallback) }
    }

    /**
     * Resultado de scan. O peerID vem do serviceData 0xFD6F e **exatamente** 8 B
     * (nunca parsear payload de tamanho inesperado — design §4).
     */
    private fun onAnunciado(result: ScanResult?) {
        val r = result ?: return
        val bruto = r.scanRecord?.getServiceData(SERVICE_UUID_16) ?: return
        if (bruto.size != BleCodec.ID_LEN) return
        val id = BleCodec.hex(bruto).lowercase()
        if (id.length != 16) return
        val meu = meuId() ?: return
        if (id == meu) return // eu mesmo

        estado {
            rssiPorId[id] = r.rssi
            conhecidos[id] = r.device
            // Gate do iniciador (design §4.3b): so o dono do peerID MAIOR conecta.
            if (id >= meu) return@estado
            if (links.containsKey(id)) return@estado // ja conectando/conectado
            val agora = System.currentTimeMillis()
            if (agora < (proximasTentativas[id] ?: 0L)) return@estado
            conectar(id, r.device, agora)
        }
        // Keep-alive: so ha sinal, mas o peer continua vivo na mesa (fora do lock).
        if (PeerTable.contem(id)) PeerTable.tocar(id)
    }

    // ------------------------------------------------------------------ protocolo

    /** ANNOUNCE com o apelido local em TLV `0x01`. */
    private fun frameAnounce(): ByteArray? {
        val meu = meuId() ?: return null
        return try {
            BleCodec.encode(
                BleCodec.ANNOUNCE,
                meuId8(meu),
                BleCodec.ZEROS,
                BleCodec.tlvApelido(IdentidadeStore.apelido)
            )
        } catch (e: IllegalStateException) {
            null
        }
    }

    private fun anunciar(a: Assinante): Boolean {
        if (a.anunciado) return true
        val ok = notificar(a, frameAnounce())
        if (ok) a.anunciado = true
        return ok
    }

    /** Decode + regras de aceite, iguais nos dois papeis (design §4.8d). */
    private fun processar(bytes: ByteArray, origem: String?) {
        val env = BleCodec.decode(bytes)
        if (env == null) {
            Log.w(TAG, "frame invalido de $origem (${bytes.size} B)")
            return
        }
        val meu = meuId() ?: return
        if (env.remetHex.equals(meu, ignoreCase = true)) return // guarda de loop
        if (!novoFrame(env.remetHex, env.ts)) return // dedupe por (senderID, timestamp)

        when (env.tipo) {
            BleCodec.ANNOUNCE -> {
                // rssi lido DENTRO do monitor: a callback chega em thread de binder.
                val rssi = estado { rssiPorId[env.remetHex] ?: 0 }
                PeerTable.registrar(env.remetHex, BleCodec.apelidoDo(env.payload), rssi)
            }

            BleCodec.MESSAGE -> {
                if (!BleCodec.enderecadoParaMim(env, meu)) return
                val texto = String(env.payload, Charsets.UTF_8)
                gravar(env.remetHex, texto)
            }

            BleCodec.LEAVE -> {
                // Peer que manda LEAVE e marcado desconectado na hora. A tabela so expoe
                // remover (sem metodo "desconectar"), entao ele sai da mesa.
                PeerTable.remover(env.remetHex)
                estado {
                    assinantes.values.filter { it.peerId == env.remetHex }
                        .forEach { assinantes.remove(it.address) }
                    links.remove(env.remetHex)?.let { fecharLink(it, null) }
                }
            }
        }
    }

    private fun gravar(peerId: String, texto: String) {
        val s = store ?: return
        s.inserir(
            Mensagem(
                autor = apelidoDe(peerId),
                conteudo = texto,
                canal = CANAL_GERAL,
                privadoPara = null,
                hora = relogio(),
                minha = false,
                entregue = true
            )
        )
        PeerTable.mensagemRecebida(peerId, texto)
    }

    private fun apelidoDe(peerId: String): String =
        PeerTable.peers().firstOrNull { it.id == peerId }?.nickname?.takeIf { it.isNotEmpty() }
            ?: peerId.take(4)

    /** LRU de 64 pares (senderID, timestamp) como cinto-e-suspensorio (design §2). */
    private fun novoFrame(senderHex: String, ts: Long): Boolean {
        val chave = "$senderHex|$ts"
        synchronized(vistosSet) {
            if (!vistosSet.add(chave)) return false
            vistos.addLast(chave)
            while (vistos.size > DEDUPE_MAX) vistosSet.remove(vistos.removeFirst())
        }
        return true
    }

    // ------------------------------------------------------------ housekeeping

    /** Tick de 15 s: expira peers mortos, poda links e religa com backoff 5/15/45. */
    private fun varrer() {
        PeerTable.expirar()
        val agora = System.currentTimeMillis()
        estado {
            links.values.filter { it.estado == ESTADO_DESCONECTADO }.forEach { links.remove(it.peerId) }
            // Link preso em CONECTANDO/INCOMPATIVEL que nunca mais fala: sem esta poda o
            // backoff morre com ele (o `conhecidos` nunca chega a reconectar).
            links.values.filter { agora - it.lastSeen > 120_000L }.toList()
                .forEach { fecharLink(it, null) }
            assinantes.values.forEach {
                it.lastSeen = agora
                anunciar(it) // cobre o announce que esbarrou na fila cheia
            }
            val meu = meuId() ?: return@estado
            for ((id, device) in conhecidos) {
                if (id >= meu) continue // o gate do iniciador tambem vale na religacao
                if (links.containsKey(id)) continue
                if (agora < (proximasTentativas[id] ?: 0L)) continue
                conectar(id, device, agora)
            }
        }
    }

    private fun fecharTudo() {
        pararScan()
        pararAdvertising()
        fecharServidor()
        estado {
            links.values.toList().forEach { fecharLink(it, null) }
            links.clear()
            assinantes.clear()
            conhecidos.clear()
            rssiPorId.clear()
            proximasTentativas.clear()
            tentativasPorId.clear()
            // Nao apaga a mesa: so marca todo mundo desconectado (a UI mantem o historico).
            PeerTable.expirar(System.currentTimeMillis() + PeerTable.EXPIRA_MS + 1L)
            adapter = null
            manager = null
        }
    }

    // ---------------------------------------------------------------- permissao

    /**
     * Executa [bloco] engolindo `SecurityException`: em API 31+ o framework lanca em
     * `gatt.*` e em `device.address` quando `BLUETOOTH_CONNECT` e revogado no meio da sessao.
     */
    private inline fun <T> comSeguranca(onde: String, bloco: () -> T): T? =
        try {
            bloco()
        } catch (e: SecurityException) {
            Log.w(TAG, "sem permissao em $onde: ${e.message}")
            null
        }

    /** `device.address` exige BLUETOOTH_CONNECT no 31+: nunca chamar cru. */
    private fun BluetoothDevice.addressSeguro(): String? = comSeguranca("device.address") { address }

    // --------------------------------------------------------------- identidade

    private fun meuId(): String? {
        if (appCtx == null) return null
        return try {
            IdentidadeStore.peerId
        } catch (e: IllegalStateException) {
            null
        }
    }

    private fun meuId8(id: String): ByteArray = BleCodec.deHexId(id) ?: ByteArray(BleCodec.ID_LEN)

    private fun relogio() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}