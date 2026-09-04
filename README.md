# RocketBridge 🚀

**Cliente leve, moderno e de código aberto para servidores Rocket.Chat com entrega garantida de notificações em tempo real sem dependência do Firebase (FCM) ou limites da Rocket.Chat Cloud.**

---

## 🎯 O Problema que o RocketBridge Resolve

Em instalações corporativas e autohospedadas (*self-hosted*) do Rocket.Chat Community (como órgãos públicos, empresas e servidores privados):
1. **O aplicativo oficial da Play Store exige gateway pago:** A Rocket.Chat impôs limites de notificações para servidores gratuitos (bloqueando alertas móveis quando a cota mensal é atingida).
2. **PWAs (WebAPKs) não notificam fechados:** Navegadores móveis derrubam a conexão WebSocket quando a tela é desligada ou o app é fechado.
3. **Usar Firebase próprio exige recompilar:** Usar FCM próprio com o app oficial requer clonar o código do React Native e recompilar o app da empresa.

**A Solução do RocketBridge:**
O RocketBridge conecta-se diretamente ao WebSocket do servidor (`/websocket`) através de um **Foreground Service nativo em Kotlin/Android**, consumindo quase zero bateria e garantindo notificações locais instantâneas para qualquer servidor, sem custos e sem Firebase.

---

## 🏗️ Arquitetura

- **Interface:** Jetpack Compose + Material Design 3.
- **Visualização Web:** Android `WebView` com aceleração por hardware, suporte a cookies, uploads e áudio.
- **Ponte de Autenticação:** Extração automática de `Meteor.loginToken` e `Meteor.userId` para iniciar a conexão nativa.
- **Serviço de Segundo Plano:** `RocketWebSocketService` (Android Foreground Service) com cliente de rede `OkHttp`.
- **Protocolo:** Meteor DDP (Distributed Data Protocol) com subscrição estrita a `stream-notify-user: <userId>/notification` (zero sobrecarga no servidor).
- **Notificações:** Notificações locais com alta prioridade e atalho para a conversa correspondente (`roomId`).

---

## 📦 Como Compilar

Requisitos:
- Java 17+ (OpenJDK)
- Android SDK (API 36 / Build-Tools 36.0.0)

Compilar o APK:
```bash
./gradlew assembleDebug
```
O arquivo final será gerado em:
`app/build/outputs/apk/debug/RocketBridge-v1.0-debug.apk`

---

## 📱 Distribuição para Usuários Finais

1. Envie o arquivo `RocketBridge-v1.0.apk` para o usuário (via WhatsApp, Telegram, e-mail ou pendrive).
2. O usuário toca no arquivo e clica em **Instalar** (ativando a permissão de fontes desconhecidas se solicitada).
3. Ao abrir:
   - Concede a permissão de **Notificações** exibida na tela.
   - Concede a permissão de **Segundo Plano (Bateria)** na janela nativa do sistema.
   - Digita a URL do servidor (ex: `https://chat.suaempresa.com` ou `https://open.rocket.chat`).
   - Faz o login habitual.
