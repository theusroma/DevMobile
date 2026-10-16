package com.example.aerotalks

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Aplica os insets do sistema ao root de uma tela, somando-os ao padding que o
 * layout ja declara.
 *
 * Motivo: com targetSdk 35+ o edge-to-edge e imposto pelo sistema e o
 * `windowSoftInputMode="adjustResize"` sozinho nao redimensiona mais a janela
 * com o teclado aberto. Sem tratar os insets no codigo, o rodape (chips + barra
 * de envio) fica ATRAS do IME (SPEC §5.1 / §10.3).
 *
 * Preserva o padding original do root e SOMA os insets a cada passada (idempotente:
 * as bordas lidas sao as do layout, nao as ja aplicadas). Usa systemBars para as
 * barras de status/navegacao e o maior valor entre `bars.bottom` e `ime.bottom`
 * para o teclado, de modo que a base acompanhe quem for mais alto.
 */
fun View.aplicarInsetsDoSistema() {
    val pl = paddingLeft
    val pt = paddingTop
    val pr = paddingRight
    val pb = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(
            pl + bars.left,
            pt + bars.top,
            pr + bars.right,
            pb + maxOf(bars.bottom, ime.bottom)
        )
        insets
    }
}
