package com.example.aerotalks

import android.app.Activity
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Diálogos montados na mão, sem XML: quem está no mesh e o "sobre".
 * Tudo bem tosco de propósito.
 */
object AndroidChatDialogs {

    /** Diálogo com a lista de vizinhos + canais. */
    fun rede(activity: Activity, peers: List<Peer>, canais: List<Canal>) {
        val raiz = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
        }

        raiz.addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    TextView(activity).apply {
                        text = "Minha id: ${MeshSimulator.meuId}"
                        textSize = 11f
                    }
                )
                addView(
                    TextView(activity).apply {
                        text = "eu: / ${MeshSimulator.eu}"
                        textSize = 11f
                    }
                )
                addView(
                    TextView(activity).apply {
                        text = "${peers.count { it.conectado }} conectados de ${peers.size}"
                        textSize = 11f
                        setPadding(0, 8, 0, 8)
                    }
                )
            }
        )

        peers.forEach { peer ->
            raiz.addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 8, 0, 8)
                    addView(
                        TextView(activity).apply {
                            text = peer.nickname
                            textSize = 15f
                        }
                    )
                    addView(
                        TextView(activity).apply {
                            text = peer.status
                            textSize = 12f
                        }
                    )
                }
            )
        }

        raiz.addView(
            TextView(activity).apply {
                text = "Canais"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 16, 0, 4)
            }
        )
        canais.forEach { canal ->
            raiz.addView(
                TextView(activity).apply {
                    text = (if (canal.dentro) "dentro · " else "fora · ") + canal.resumo
                    textSize = 12f
                    setPadding(0, 2, 0, 2)
                }
            )
        }

        AlertDialog.Builder(activity)
            .setTitle("Mesh local")
            .setView(ScrollView(activity).apply { addView(raiz) })
            .setPositiveButton("FECHAR", null)
            .show()
    }

    /** Diálogo "sobre": o que é o AeroTalks. */
    fun about(activity: Activity) {
        val texto = TextView(activity).apply {
            setPadding(40, 24, 40, 8)
            textSize = 13f
            text = buildString {
                append("AeroTalks\n")
                append("Chat por mesh local: o aparelho conversa direto com quem está ")
                append("por perto, sem internet, sem servidor e sem conta.\n\n")
                append("Aqui: RASCUNHO. O mesh é simulado no aparelho — ")
                append("nenhum pacote sai daqui.\n\n")
                append("Como funciona\n")
                append("· vizinhos por perto, cada um com força do sinal e saltos\n")
                append("· canais #geral, #cafeteria, #prox-unicamp e #cripto\n")
                append("· menções e mensagens privadas\n")
                append("· transcrição guardada em SQLite local")
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("AeroTalks — rascunho")
            .setView(texto)
            .setPositiveButton("FECHAR", null)
            .show()
    }
}