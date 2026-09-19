package com.zangi.chat.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zangi.chat.data.repository.ChatRepository
import com.zangi.chat.databinding.ActivityOnboardingBinding
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var repository: ChatRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ChatRepository.getInstance()

        // Se já tiver usuário registrado, vai direto para a MainActivity
        if (repository.isUserRegistered()) {
            openMainActivity()
            return
        }

        // Animações de entrada suaves
        binding.logoContainer.scaleX = 0.5f
        binding.logoContainer.scaleY = 0.5f
        binding.logoContainer.alpha = 0f
        binding.logoContainer.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(500)
            .setInterpolator(android.view.animation.OvershootInterpolator())
            .start()

        binding.layoutForm.alpha = 0f
        binding.layoutForm.translationY = 30f
        binding.layoutForm.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setStartDelay(200)
            .start()

        binding.btnGenerateNumber.setOnClickListener {
            val nick = binding.etNickname.text.toString().trim()
            if (nick.isEmpty()) {
                Toast.makeText(this, "Informe seu Nickname para continuar.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnGenerateNumber.isEnabled = false
            binding.btnGenerateNumber.text = "Gerando seu número privado..."

            lifecycleScope.launch {
                val result = repository.registerUser(nick)
                binding.btnGenerateNumber.isEnabled = true
                binding.btnGenerateNumber.text = "Gerar Meu Número Zangi (10 Dígitos)"

                if (result.isSuccess) {
                    val user = result.getOrNull()
                    if (user != null) {
                        binding.tvGeneratedNumber.text = user.zangiNumber
                        binding.cardRevealNumber.visibility = View.VISIBLE
                        val anim = android.view.animation.AnimationUtils.loadAnimation(
                            this@OnboardingActivity,
                            com.zangi.chat.R.anim.anim_scale_fade_in
                        )
                        binding.cardRevealNumber.startAnimation(anim)
                        binding.layoutForm.visibility = View.GONE
                    }
                } else {
                    Toast.makeText(
                        this@OnboardingActivity,
                        result.exceptionOrNull()?.message ?: "Erro ao registrar.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        binding.btnEnterApp.setOnClickListener {
            openMainActivity()
        }
    }

    private fun openMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
