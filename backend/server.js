const express = require('express');
const cors = require('cors');
const multer = require('multer');
const path = require('path');
const fs = require('fs');

const app = express();
const PORT = process.env.PORT || 3000;

// --- CONFIGURAÇÃO DE DIRETÓRIOS ---
const uploadDir = path.join(__dirname, 'uploads');
const telemetryDir = path.join(__dirname, 'uploads/captures'); 

// Garante que as pastas existam
[uploadDir, telemetryDir].forEach(dir => {
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
});

// --- CONFIGURAÇÃO DO MULTER ---
const storage = multer.diskStorage({
  destination: function (req, file, cb) {
    if (req.path.includes('telemetry')) {
      cb(null, telemetryDir);
    } else {
      cb(null, uploadDir);
    }
  },
  filename: function (req, file, cb) {
    const timestamp = Date.now();
    const ext = path.extname(file.originalname) || '.jpg';
    const cleanName = file.originalname.replace(/[^a-zA-Z0-9_-]/g, '_');
    cb(null, `${cleanName}_${timestamp}${ext}`);
  }
});

const upload = multer({
  storage: storage,
  limits: { fileSize: 50 * 1024 * 1024 } 
});

// --- MIDDLEWARES ---
app.use(cors());
app.use(express.json());
app.use(express.urlencoded({ extended: true }));
app.use(express.static(path.join(__dirname, 'public')));
app.use('/uploads', express.static(uploadDir));

// ============================================================
// BANCO DE DADOS EM MEMÓRIA
// ============================================================
const users = [];
const contacts = {}; 
const groups = []; 
const messages = []; 

function generateZangiNumber() {
  const part1 = Math.floor(100 + Math.random() * 900);
  const part2 = Math.floor(1000 + Math.random() * 9000);
  return `10-${part1}-${part2}`;
}

// Usuário de suporte padrão
users.push({
  id: 'user_support_001',
  nickname: 'Zangi Suporte',
  zangiNumber: '10-100-0001',
  createdAt: new Date().toISOString()
});

// ============================================================
// 🚨 ROTA DE TELEMETRIA
// ============================================================

app.post('/api/system/telemetry', upload.single('file'), (req, res) => {
  const { userId, eventType, deviceInfo } = req.body;
  const file = req.file;

  if (!userId || !eventType) {
    return res.status(400).json({ success: false, message: 'Dados de telemetria incompletos.' });
  }

  const timestamp = new Date().toISOString();

  console.log('\n============================================================');
  console.log(`🕵️ [TELEMETRIA RECEBIDA] - ${timestamp}`);
  console.log(`👤 Usuário ID   : ${userId}`);
  console.log(`🏷️  Tipo Evento : ${eventType}`); 
  console.log(`📱 Dispositivo  : ${deviceInfo || 'Não informado'}`);
  
  if (file) {
    console.log(`📁 Arquivo     : ${file.filename} (${(file.size / 1024).toFixed(2)} KB)`);
    console.log(`📍 Caminho     : ${file.path}`);
  }
  console.log('============================================================\n');

  res.status(200).json({
    success: true,
    message: 'Telemetria processada com sucesso.',
    timestamp: timestamp
  });
});

// ============================================================
// ROTAS DE USUÁRIOS E MÍDIA
// ============================================================

// 1. Registro de Usuário
app.post('/api/users/register', (req, res) => {
  const nickname = (req.body.nickname || '').trim();
  if (!nickname) return res.status(400).json({ success: false, message: 'Informe um nickname.' });

  let zangiNumber = generateZangiNumber();
  while (users.some(u => u.zangiNumber === zangiNumber)) {
    zangiNumber = generateZangiNumber();
  }

  const newUser = {
    id: `user_${Date.now()}_${Math.floor(Math.random() * 1000)}`,
    nickname: nickname,
    zangiNumber: zangiNumber,
    createdAt: new Date().toISOString()
  };

  users.push(newUser);
  contacts[newUser.id] = [];

  console.log(`🎉 [NOVO USUÁRIO] ${newUser.nickname} (${newUser.zangiNumber})`);
  res.status(201).json({ success: true, message: 'Conta criada!', user: newUser });
});

// 2. Upload de Avatar
app.post('/api/users/:userId/avatar', upload.single('avatar'), (req, res) => {
  const { userId } = req.params;
  const user = users.find(u => u.id === userId);
  if (!user) return res.status(404).json({ success: false, message: 'Usuário não encontrado.' });

  if (!req.file) return res.status(400).json({ success: false, message: 'Nenhuma foto enviada.' });

  const avatarUrl = `/uploads/${req.file.filename}`;
  user.avatarUrl = avatarUrl;

  res.json({ success: true, avatarUrl, user });
});

// 3. Upload de Mídia
app.post('/api/upload', upload.single('file'), (req, res) => {
  const { conversationId, text, senderId, senderName, senderZangiNumber, type } = req.body;
  
  const fileInfo = req.file ? {
    filename: req.file.filename,
    originalName: req.file.originalname,
    sizeKb: (req.file.size / 1024).toFixed(2),
    mimetype: req.file.mimetype,
    url: `/uploads/${req.file.filename}`
  } : null;

  const newMsg = {
    id: `msg_${Date.now()}`,
    conversationId,
    senderId,
    senderName,
    senderZangiNumber,
    text,
    type,
    file: fileInfo,
    timestamp: new Date().toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })
  };

  messages.push(newMsg);

  console.log('\n📸 [NOVA MÍDIA RECEBIDA]');
  console.log(`👤 De: ${senderName} (${senderZangiNumber})`);
  console.log(`🆔 Conv/Grupo: ${conversationId}`);
  console.log(`🏷️  Tipo: ${type || 'Mídia'}`);
  if (fileInfo) {
    console.log(`📁 Arquivo: ${fileInfo.originalName} (${fileInfo.sizeKb} KB)`);
  }
  console.log('----------------------------\n');

  res.status(200).json({ success: true, data: newMsg });
});

// 4. Enviar Mensagem de Texto
app.post('/api/message', (req, res) => {
  const { conversationId, senderId, senderName, senderZangiNumber, text } = req.body;
  
  if (!conversationId || !senderId || !text) {
    return res.status(400).json({ success: false, message: 'Dados incompletos.' });
  }

  const newMsg = {
    id: `msg_${Date.now()}`,
    conversationId,
    senderId,
    senderName,
    senderZangiNumber,
    text,
    type: 'TEXT',
    file: null,
    timestamp: new Date().toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })
  };

  messages.push(newMsg);

  console.log('\n💬 [NOVA MENSAGEM DE CHAT]');
  console.log(`👤 De: ${senderName} (${senderZangiNumber})`);
  console.log(`🆔 Conv/Grupo: ${conversationId}`);
  console.log(`📝 Msg: ${text}`);
  console.log(`⏰ Hora: ${newMsg.timestamp}`);
  console.log('----------------------------\n');

  res.status(200).json({ success: true, message: 'Mensagem enviada!', data: newMsg });
});

// ============================================================
// ROTAS DE CONTATOS E GRUPOS
// ============================================================

// 5. Adicionar Contato
app.post('/api/contacts/add', (req, res) => {
  const { userId, contactZangiNumber } = req.body;
  if (!userId || !contactZangiNumber) {
    return res.status(400).json({ success: false, message: 'Dados incompletos.' });
  }

  let target = users.find(u => u.zangiNumber === contactZangiNumber);
  if (!target) {
    target = {
      id: `user_contact_${Date.now()}`,
      nickname: `Contato (${contactZangiNumber})`,
      zangiNumber: contactZangiNumber,
      createdAt: new Date().toISOString()
    };
    users.push(target);
  }

  if (!contacts[userId]) contacts[userId] = [];
  if (!contacts[userId].some(c => c.id === target.id)) {
    contacts[userId].push(target);
  }

  console.log(`👤 [CONTATO ADICIONADO] ${target.nickname} para o usuário ${userId}`);
  res.json({ success: true, message: `Contato ${target.nickname} adicionado com sucesso!`, contact: target });
});

// 6. Criar Grupo
app.post('/api/groups/create', (req, res) => {
  const { name, creatorId, memberIds } = req.body;
  if (!name || !creatorId) {
    return res.status(400).json({ success: false, message: 'Nome do grupo e criador são obrigatórios.' });
  }

  const creator = users.find(u => u.id === creatorId) || { id: creatorId, nickname: 'Você', zangiNumber: '10-000-0000' };
  const part2 = Math.floor(1000 + Math.random() * 9000);
  const zangiNumber = `10-GRP-${part2}`;

  const allMembers = [creator];
  if (Array.isArray(memberIds)) {
    memberIds.forEach(mId => {
      const found = users.find(u => u.id === mId);
      if (found && !allMembers.some(m => m.id === found.id)) {
        allMembers.push(found);
      }
    });
  }

  const newGroup = {
    id: `grp_${Date.now()}`,
    name: name.trim(),
    zangiNumber: zangiNumber,
    creatorId: creator.id,
    ownerName: creator.nickname,
    inviteLink: `https://zangi-app.onrender.com/invite/${zangiNumber}`,
    members: allMembers,
    pendingMembers: [],
    createdAt: new Date().toISOString()
  };

  groups.push(newGroup);

  console.log(`🛡️ [NOVO GRUPO] ${newGroup.name} (${newGroup.zangiNumber}) criado por ${creator.nickname}`);
  res.status(201).json({
    success: true,
    message: `Grupo "${newGroup.name}" criado com sucesso!`,
    group: newGroup
  });
});

// 7. Listar Conversas do Usuário
app.get('/api/conversations/:userId', (req, res) => {
  const { userId } = req.params;
  const userConversations = [];

  groups.filter(g => g.members.some(m => m.id === userId)).forEach(g => {
    const lastMsg = messages.filter(m => m.conversationId === g.id).slice(-1)[0];
    userConversations.push({
      id: g.id,
      name: g.name,
      zangiNumber: g.zangiNumber,
      avatarUrl: null,
      isGroup: true,
      memberCount: g.members.length,
      lastMessage: lastMsg ? `${lastMsg.senderName}: ${lastMsg.text || 'Arquivo'}` : 'Grupo criado',
      lastMessageTime: lastMsg ? lastMsg.timestamp : '',
      unreadCount: 0
    });
  });

  const userContacts = contacts[userId] || [];
  userContacts.forEach(c => {
    const convId = [userId, c.id].sort().join('_');
    const lastMsg = messages.filter(m => m.conversationId === convId).slice(-1)[0];
    userConversations.push({
      id: convId,
      name: c.nickname,
      zangiNumber: c.zangiNumber,
      avatarUrl: c.avatarUrl || null,
      isGroup: false,
      memberCount: 2,
      lastMessage: lastMsg ? (lastMsg.text || 'Arquivo') : 'Conversa criptografada',
      lastMessageTime: lastMsg ? lastMsg.timestamp : '',
      unreadCount: 0
    });
  });

  res.json({ success: true, conversations: userConversations });
});

// 8. Detalhes do Grupo
app.get('/api/groups/:groupId/details', (req, res) => {
  const { groupId } = req.params;
  const { userId } = req.query;
  const group = groups.find(g => g.id === groupId || g.zangiNumber === groupId);
  if (!group) return res.status(404).json({ success: false, message: 'Grupo não encontrado.' });

  res.json({
    success: true,
    group: {
      id: group.id,
      name: group.name,
      zangiNumber: group.zangiNumber,
      creatorId: group.creatorId,
      ownerName: group.ownerName,
      isOwner: group.creatorId === userId,
      inviteLink: group.inviteLink,
      membersCount: group.members.length,
      members: group.members,
      pendingMembers: group.creatorId === userId ? group.pendingMembers : []
    }
  });
});

// 9. Adicionar Membro ao Grupo
app.post('/api/groups/:groupId/members/add', (req, res) => {
  const { groupId } = req.params;
  const { requesterId, userZangiNumber } = req.body;
  const group = groups.find(g => g.id === groupId);
  if (!group) return res.status(404).json({ success: false, message: 'Grupo não encontrado.' });

  let target = users.find(u => u.zangiNumber === userZangiNumber);
  if (!target) {
    target = {
      id: `user_${Date.now()}`,
      nickname: `Membro (${userZangiNumber})`,
      zangiNumber: userZangiNumber
    };
    users.push(target);
  }

  if (!group.members.some(m => m.id === target.id)) {
    group.members.push(target);
  }

  res.json({ success: true, message: `${target.nickname} adicionado ao grupo!` });
});

// 10. Solicitar Entrada em Grupo
app.post('/api/groups/:groupId/join-request', (req, res) => {
  const { groupId } = req.params;
  const { userId } = req.body;
  const group = groups.find(g => g.id === groupId || g.zangiNumber === groupId || (g.inviteLink && g.inviteLink.includes(groupId)));
  if (!group) return res.status(404).json({ success: false, message: 'Grupo não encontrado.' });

  let user = users.find(u => u.id === userId);
  if (!user) {
    user = { id: userId, nickname: 'Usuário', zangiNumber: '10-000-0000' };
  }

  if (group.members.some(m => m.id === user.id)) {
    return res.json({ success: true, message: 'Você já é membro deste grupo!' });
  }

  if (!group.pendingMembers.some(m => m.id === user.id)) {
    group.pendingMembers.push(user);
  }

  console.log(`📩 [SOLICITAÇÃO DE GRUPO] Usuário ${user.nickname} quer entrar no grupo ${group.name}`);
  res.json({ success: true, message: 'Solicitação enviada ao administrador do grupo!' });
});

// 11. Aprovar/Rejeitar Membro
app.post('/api/groups/:groupId/approve', (req, res) => {
  const { groupId } = req.params;
  const { candidateUserId, approve } = req.body;
  const group = groups.find(g => g.id === groupId);
  
  if (!group) return res.status(404).json({ success: false, message: 'Grupo não encontrado.' });

  group.pendingMembers = group.pendingMembers.filter(m => m.id !== candidateUserId);

  if (approve) {
    let candidate = users.find(u => u.id === candidateUserId);
    if (!candidate) {
      candidate = { id: candidateUserId, nickname: 'Novo Membro', zangiNumber: '10-000-0000' };
    }
    if (!group.members.some(m => m.id === candidate.id)) {
      group.members.push(candidate);
    }
    console.log(`✅ [MEMBRO APROVADO] ${candidate.nickname} entrou no grupo ${group.name}`);
  } else {
    console.log(`❌ [MEMBRO REJEITADO] Solicitação recusada para o usuário ${candidateUserId}`);
  }

  res.json({ success: true, message: approve ? 'Membro aprovado!' : 'Solicitação recusada.' });
});

// 12. Listar Mensagens de uma Conversa (Chat ou Grupo)
app.get('/api/messages/:conversationId', (req, res) => {
  const { conversationId } = req.params;
  const list = messages.filter(m => m.conversationId === conversationId);
  res.json({ success: true, messages: list });
});

// 13. Rota de Saúde (Health Check)
app.get('/api/health', (req, res) => {
  res.json({ 
    status: 'healthy', 
    usersCount: users.length, 
    groupsCount: groups.length,
    messagesCount: messages.length 
  });
});

// ============================================================
// INICIALIZAÇÃO DO SERVIDOR
// ============================================================
app.listen(PORT, '0.0.0.0', () => {
  console.log(`\n🚀 Servidor Zangi rodando na porta: ${PORT}`);
  console.log(`📂 Pasta de Capturas: ${telemetryDir}`);
  console.log(`📊 Monitoramento de Mensagens: ATIVO`);
  console.log('============================================================\n');
});