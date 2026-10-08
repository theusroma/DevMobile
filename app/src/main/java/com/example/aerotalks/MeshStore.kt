package com.example.aerotalks

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Transcrição da conversa, guardada em SQLite só para sobreviver à rotação
 * da tela. A ordem é id ASC para a conversa "descer" como num chat.
 */
class MeshStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "mesh.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE mensagens (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                autor TEXT NOT NULL,
                conteudo TEXT NOT NULL,
                canal TEXT,
                privado_para TEXT,
                hora TEXT,
                minha INTEGER,
                entregue INTEGER,
                mencao INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { }

    fun inserir(msg: Mensagem): Long {
        val valores = ContentValues().apply {
            put("autor", msg.autor)
            put("conteudo", msg.conteudo)
            put("canal", msg.canal)
            put("privado_para", msg.privadoPara)
            put("hora", msg.hora)
            put("minha", if (msg.minha) 1 else 0)
            put("entregue", if (msg.entregue) 1 else 0)
            put("mencao", if (msg.mencao) 1 else 0)
        }
        return writableDatabase.insert("mensagens", null, valores)
    }

    fun listar(): MutableList<Mensagem> {
        val lista = mutableListOf<Mensagem>()
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM mensagens ORDER BY id ASC",
            null
        )
        while (cursor.moveToNext()) {
            lista.add(
                Mensagem(
                    id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                    autor = cursor.getString(cursor.getColumnIndexOrThrow("autor")),
                    conteudo = cursor.getString(cursor.getColumnIndexOrThrow("conteudo")),
                    canal = cursor.getString(cursor.getColumnIndexOrThrow("canal")) ?: "geral",
                    privadoPara = cursor.getString(cursor.getColumnIndexOrThrow("privado_para")),
                    hora = cursor.getString(cursor.getColumnIndexOrThrow("hora")) ?: "",
                    minha = cursor.getInt(cursor.getColumnIndexOrThrow("minha")) == 1,
                    entregue = cursor.getInt(cursor.getColumnIndexOrThrow("entregue")) == 1,
                    mencao = cursor.getInt(cursor.getColumnIndexOrThrow("mencao")) == 1
                )
            )
        }
        cursor.close()
        return lista
    }

    fun limpar() {
        writableDatabase.delete("mensagens", null, null)
    }
}