package com.example.aerotalks

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Lista de mensagens em baloes. Um so layout de item: o balao vira enviado/recebido
 * no bind.
 */
class MensagemAdapter : RecyclerView.Adapter<MensagemAdapter.MensagemViewHolder>() {

    private val mensagens = mutableListOf<Mensagem>()

    class MensagemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val balao: LinearLayout = view.findViewById(R.id.balao)
        val autor: TextView = view.findViewById(R.id.tvAutor)
        val nome: TextView = view.findViewById(R.id.tvNomeEvento)
        val dataLocal: TextView = view.findViewById(R.id.tvDataLocal)
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
        val contexto = holder.itemView.context

        // Enviada: balao cinza, alinhado a direita, sem linha de autor.
        // Recebida: balao claro com borda, alinhado a esquerda, com autor.
        holder.balao.background = contexto.getDrawable(
            if (msg.minha) R.drawable.balao_enviado else R.drawable.balao_recebido
        )
        (holder.balao.layoutParams as LinearLayout.LayoutParams).gravity =
            if (msg.minha) Gravity.END else Gravity.START

        holder.autor.visibility = if (msg.minha) View.GONE else View.VISIBLE
        holder.autor.text = msg.autor

        holder.nome.text = msg.conteudo

        // TODO: implementar reenvio quando vizinho aparecer — ver TODO.md
        holder.dataLocal.text = buildString {
            append(msg.hora)
            append(" · ")
            append(if (msg.ehPrivada) "privado → ${msg.privadoPara}" else "#${msg.canal}")
            if (msg.mencao) append(" · menção")
            if (!msg.entregue) append(" · reenvio quando vizinho aparecer")
        }

        // Largura maxima ~78% da lista: limita os TextViews de dentro do balao.
        val largura = (holder.itemView.parent as? View)?.width?.takeIf { it > 0 }
            ?: contexto.resources.displayMetrics.widthPixels
        val maxBalao = (largura * 0.78f).toInt()
        holder.nome.maxWidth = maxBalao
        holder.dataLocal.maxWidth = maxBalao
        holder.autor.maxWidth = maxBalao
    }

    override fun getItemCount() = mensagens.size
}
