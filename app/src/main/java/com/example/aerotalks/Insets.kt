package com.example.aerotalks

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Aplica os insets do sistema ao root de uma tela, somando-os ao padding que o
 * layout ja declara.
 *
 * Usa `systemBars` para as barras e o maior valor entre `bars.bottom` e `ime.bottom`
 * para o teclado. As bordas lidas sao as do layout, nao as ja aplicadas, entao o
 * padding original e preservado a cada passada.
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
