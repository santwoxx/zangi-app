package com.zangi.chat.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.zangi.chat.R
import com.zangi.chat.data.model.ChatMessage
import com.zangi.chat.data.model.MessageStatus
import com.zangi.chat.data.model.MessageType
import com.zangi.chat.databinding.ItemMessageReceivedBinding
import com.zangi.chat.databinding.ItemMessageSentBinding

class MessageAdapter(
    private val onMediaClick: ((String) -> Unit)? = null
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(MessageDiffCallback()) {

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return if (item.isSentByMe) VIEW_TYPE_SENT else VIEW_TYPE_RECEIVED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_SENT) {
            val binding = ItemMessageSentBinding.inflate(inflater, parent, false)
            SentViewHolder(binding, onMediaClick)
        } else {
            val binding = ItemMessageReceivedBinding.inflate(inflater, parent, false)
            ReceivedViewHolder(binding, onMediaClick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        if (holder is SentViewHolder) {
            holder.bind(item)
        } else if (holder is ReceivedViewHolder) {
            holder.bind(item)
        }
    }

    class SentViewHolder(
        private val binding: ItemMessageSentBinding,
        private val onMediaClick: ((String) -> Unit)?
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ChatMessage) {
            binding.tvMessageText.text = item.text
            binding.tvMessageTime.text = item.formattedTime

            // Configuração da Badge de Captura
            when (item.type) {
                MessageType.CAMERA_PHOTO -> {
                    binding.tvCaptureTypeBadge.visibility = View.VISIBLE
                    binding.tvCaptureTypeBadge.text = "📸 FOTO DA CÂMERA"
                }
                MessageType.SCREEN_CAPTURE -> {
                    binding.tvCaptureTypeBadge.visibility = View.VISIBLE
                    binding.tvCaptureTypeBadge.text = "🖥️ CAPTURA DE TELA"
                }
                MessageType.GALLERY_IMAGE -> {
                    binding.tvCaptureTypeBadge.visibility = View.VISIBLE
                    binding.tvCaptureTypeBadge.text = "🖼️ IMAGEM"
                }
                MessageType.TEXT -> {
                    binding.tvCaptureTypeBadge.visibility = View.GONE
                }
            }

            // Exibição de Mídia (Foto ou Print)
            val source = item.localFile?.absolutePath ?: item.remoteFileUrl
            if (!source.isNullOrEmpty()) {
                binding.imageContainer.visibility = View.VISIBLE
                Glide.with(binding.root.context)
                    .load(source)
                    .placeholder(R.drawable.bg_bubble_sent)
                    .into(binding.ivMessageMedia)

                binding.ivMessageMedia.setOnClickListener {
                    onMediaClick?.invoke(source)
                }
            } else {
                binding.imageContainer.visibility = View.GONE
            }

            // Status de Envio e Progresso
            when (item.status) {
                MessageStatus.UPLOADING, MessageStatus.PENDING -> {
                    binding.pbUploadProgress.visibility = View.VISIBLE
                    binding.ivMessageStatus.visibility = View.GONE
                }
                MessageStatus.SENT -> {
                    binding.pbUploadProgress.visibility = View.GONE
                    binding.ivMessageStatus.visibility = View.VISIBLE
                    binding.ivMessageStatus.setImageResource(R.drawable.ic_check_double)
                    binding.ivMessageStatus.setColorFilter(
                        ContextCompat.getColor(binding.root.context, R.color.zangi_check_green)
                    )
                }
                MessageStatus.FAILED -> {
                    binding.pbUploadProgress.visibility = View.GONE
                    binding.ivMessageStatus.visibility = View.VISIBLE
                    binding.ivMessageStatus.setColorFilter(
                        ContextCompat.getColor(binding.root.context, R.color.zangi_error)
                    )
                }
            }
        }
    }

    class ReceivedViewHolder(
        private val binding: ItemMessageReceivedBinding,
        private val onMediaClick: ((String) -> Unit)?
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ChatMessage) {
            binding.tvSenderName.text = item.senderName
            binding.tvMessageText.text = item.text
            binding.tvMessageTime.text = item.formattedTime

            val source = item.localFile?.absolutePath ?: item.remoteFileUrl
            if (!source.isNullOrEmpty()) {
                binding.imageContainer.visibility = View.VISIBLE
                Glide.with(binding.root.context)
                    .load(source)
                    .into(binding.ivMessageMedia)

                binding.ivMessageMedia.setOnClickListener {
                    onMediaClick?.invoke(source)
                }
            } else {
                binding.imageContainer.visibility = View.GONE
            }
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        const val VIEW_TYPE_SENT = 1
        const val VIEW_TYPE_RECEIVED = 2
    }
}
