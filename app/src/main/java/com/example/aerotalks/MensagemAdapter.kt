package com.example.aerotalks

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Lista da transcrição. Reaproveita item_evento.xml:
 * tvNomeEvento vira a linha da mensagem, tvDataLocal vira o metadado.
 */
class MensagemAdapter : RecyclerView.Adapter<MensagemAdapter.MensagemViewHolder>() {

    private val mensagens = mutableListOf<Mensagem>()

    class MensagemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nome: TextView = view.findViewById<TextView>(R.id.tvNomeEvento)
        val dataLocal: TextView = view.findViewById<TextView>(R.id.tvDataLocal)
    }

    fun adicionar(msg: Mensagem) {
        mensagens.add(msg)
        notifyItemInserted(mensagens.size - 1)
    }

    fun carregar(lista: List<Mensagem>) {
        mensagens.clear()
        mensagens.addAll(lista)
        notifyDataSetChanged()
    }

    fun limpar() {
        mensagens.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MensagemViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_evento, parent, false)
        return MensagemViewHolder(view)
    }

    override fun onBindViewHolder(holder: MensagemViewHolder, position: Int) {
        val msg = mensagens[position]

        holder.nome.text = "${msg.hora}  ${msg.autor}: ${msg.conteudo}"
        holder.dataLocal.text = buildString {
            append(if (msg.privadoPara != null) "privado → ${msg.privadoPara}" else "#${msg.canal}")
            if (msg.mencao) append(" · menção")
            if (!msg.entregue) append(" · aguardando repasse")
        }
    }

    override fun getItemCount() = mensagens.size
}