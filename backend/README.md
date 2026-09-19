# Backend - Zangi Chat API (Render Ready)

Backend simples e veloz construído com **Node.js + Express + Multer** para recepção de mensagens de texto e arquivos (fotos de câmera e capturas de tela) com logs formatados no console.

## 🚀 Como Rodar Localmente

1. Navegue até a pasta `backend`:
   ```bash
   cd backend
   ```
2. Instale as dependências:
   ```bash
   npm install
   ```
3. Inicie o servidor:
   ```bash
   npm start
   ```
   O servidor estará rodando em `http://localhost:3000`.

---

## 📡 Endpoints da API

### 1. `POST /api/upload` (Principal)
Recebe dados via `multipart/form-data`.
- **Campos do formulário:**
  - `file`: Arquivo binário (imagem da câmera `.jpg` ou captura de tela `.png`).
  - `text`: Texto da mensagem ou legenda.
  - `sender`: Nome do remetente (ex: `"Alexander M."` ou `"Dispositivo Android"`).
  - `type`: Tipo de envio (`"TEXTO"`, `"FOTO_CAMERA"`, `"CAPTURA_TELA"`).
- **Resposta (200 OK):**
  ```json
  {
    "success": true,
    "message": "Dados recebidos com sucesso no servidor!",
    "timestamp": "18/09/2026 19:47:00",
    "data": {
      "text": "Foto da câmera enviada com sucesso",
      "sender": "Alexander M.",
      "type": "FOTO_CAMERA",
      "file": {
        "filename": "camera_1716900000000.jpg",
        "sizeKb": "145.20",
        "mimetype": "image/jpeg",
        "url": "/uploads/camera_1716900000000.jpg"
      }
    }
  }
  ```

### 2. `POST /api/message`
Recebe mensagens simples apenas de texto via JSON (`{"text": "olá"}`).

### 3. `GET /` & `GET /api/health`
Retorna status de conexão e saúde da API.

---

## ☁️ Como Fazer Deploy no Render (Gratuito)

1. Suba o código para um repositório no **GitHub** ou **GitLab**.
2. Acesse [render.com](https://render.com) e crie uma conta gratuita.
3. Clique em **New +** -> **Web Service**.
4. Conecte seu repositório e selecione a subpasta `backend` como **Root Directory** (ou configure na raiz).
5. Configurações:
   - **Environment:** Node
   - **Build Command:** `npm install`
   - **Start Command:** `node server.js`
6. Clique em **Create Web Service**.
7. O Render fornecerá uma URL pública HTTPS (exemplo: `https://zangi-chat-backend.onrender.com`).
8. Cole essa URL no arquivo `RetrofitClient.kt` do app Android!
