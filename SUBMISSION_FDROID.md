# Guia de Publicação no F-Droid e IzzyOnDroid 🚀

Este guia detalha o passo a passo para disponibilizar o **RocketBridge** na rede do F-Droid.

---

## 1. Subindo o Código no seu GitHub

Substitua `petersonbasso` pelo seu nome de usuário no GitHub:

```bash
cd /home/peterson/RocketBridge

# 1. Inicializar o Git se ainda não estiver inicializado
git init

# 2. Adicionar todos os arquivos
git add .
git commit -m "feat: initial release of RocketBridge v1.0.0"

# 3. Definir branch principal e apontar para o seu repositório
git branch -M main
git remote add origin https://github.com/petersonbasso/rocketbridge.git

# 4. Enviar os arquivos
git push -u origin main

# 5. Criar a tag da versão 1.0.0 (O GitHub Actions compilará o APK automaticamente!)
git tag v1.0.0
git push origin v1.0.0
```

> **Dica:** Assim que você rodar o comando `git push origin v1.0.0`, o GitHub Actions entrará em ação e gerará automaticamente o arquivo `RocketBridge.apk` na aba **Releases** do seu repositório.

---

## 2. Inclusão no IzzyOnDroid (Aprovação em 24h a 48h)

1. Acesse o repositório de submissões do IzzyOnDroid:  
   👉 **https://gitlab.com/IzzyOnDroid/repo/-/issues**
2. Clique em **New Issue** (Nova Solicitação).
3. No campo de template, escolha **App Inclusion Request** e preencha:
   - **Application Name:** RocketBridge
   - **Package Name:** io.rocketbridge
   - **Source Code URL:** https://github.com/petersonbasso/rocketbridge
   - **Release / APK URL:** https://github.com/petersonbasso/rocketbridge/releases
   - **License:** MIT
   - **Short Description:** Lightweight Rocket.Chat client with background push notifications without Firebase.
4. Envie a solicitação. Em 24 a 48 horas, o bot do Izzy incluirá o app e ele aparecerá no F-Droid de todos os usuários!

---

## 3. Inclusão no F-Droid Central Oficial

Quando quiser enviar para o catálogo principal do F-Droid:
1. Faça um Fork de: `https://gitlab.com/fdroid/fdroiddata`
2. Crie o arquivo `metadata/io.rocketbridge.yml`:

```yaml
Categories:
  - Internet
  - Connectivity
License: MIT
WebSite: https://github.com/petersonbasso/rocketbridge
SourceCode: https://github.com/petersonbasso/rocketbridge
IssueTracker: https://github.com/petersonbasso/rocketbridge/issues

AutoName: RocketBridge
Summary: Lightweight Rocket.Chat client with background push notifications

Description: |
  RocketBridge is an independent, lightweight, open-source Android client designed
  for self-hosted and community Rocket.Chat servers.
  It uses a native Android Foreground Service to maintain a persistent WebSocket
  connection, delivering real-time push notifications without requiring Google
  Firebase (FCM) or paid Rocket.Chat Cloud push gateways.

RepoType: git
Repo: https://github.com/petersonbasso/rocketbridge.git

Builds:
  - versionName: '1.0'
    versionCode: 1
    commit: v1.0.0
    subdir: app
    gradle:
      - assembleDebug

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: '1.0'
CurrentVersionCode: 1
```

3. Abra um **Merge Request** para a branch principal do `fdroiddata`.
