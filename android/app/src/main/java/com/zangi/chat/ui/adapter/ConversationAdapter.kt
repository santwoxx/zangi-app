package com.zangi.chat.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zangi.chat.data.model.Conversation
import com.zangi.chat.databinding.ItemConversationBinding

class ConversationAdapter(
    private val onConversationClick: (Conversation) -> Unit
) : ListAdapter<Conversation, ConversationAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemConversationBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Conversation) {
            binding.tvConvTitle.text = item.name
            binding.tvConvTime.text = item.lastMessageTime
            binding.tvConvLastMessage.text = item.lastMessage
            binding.tvConvZangiNumber.text = item.zangiNumber

            if (!item.avatarUrl.isNullOrEmpty()) {
                binding.ivAvatarPhoto.visibility = android.view.View.VISIBLE
                binding.tvAvatarInitials.visibility = android.view.View.GONE
                com.bumptech.glide.Glide.with(binding.root.context)
                    .load(item.avatarUrl)
                    .circleCrop()
                    .into(binding.ivAvatarPhoto)
            } else {
                binding.ivAvatarPhoto.visibility = android.view.View.GONE
                binding.tvAvatarInitials.visibility = android.view.View.VISIBLE
                if (item.isGroup) {
                    binding.tvAvatarInitials.text = "🛡️"
                } else {
                    binding.tvAvatarInitials.text = item.name.take(1).uppercase()
                }
            }

            binding.root.setOnClickListener {
                onConversationClick(item)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Conversation>() {
        override fun areItemsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
            return oldItem == newItem
        }
    }
}
