package io.rocketbridge.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RocketMediaCacheManagerTest {

    @Test
    fun testShouldIntercept_rocketchatMediaUrls() {
        // Uploads
        assertTrue(RocketMediaCacheManager.shouldIntercept("https://chat.example.com/file-upload/abc123xyz/photo.png"))
        assertTrue(RocketMediaCacheManager.shouldIntercept("https://chat.example.com/file-upload/abc123xyz/document.pdf?download=true"))
        assertTrue(RocketMediaCacheManager.shouldIntercept("http://chat.example.com/ufs/GridFS:Uploads/12345/image.jpeg"))

        // Avatares
        assertTrue(RocketMediaCacheManager.shouldIntercept("https://chat.example.com/avatar/john.doe"))
        assertTrue(RocketMediaCacheManager.shouldIntercept("https://chat.example.com/avatar/room123?format=png"))

        // Imagens estáticas
        assertTrue(RocketMediaCacheManager.shouldIntercept("https://cdn.example.com/assets/logo.png"))
        assertTrue(RocketMediaCacheManager.shouldIntercept("https://cdn.example.com/img/banner.webp"))
        assertTrue(RocketMediaCacheManager.shouldIntercept("https://cdn.example.com/img/icon.svg"))
        assertTrue(RocketMediaCacheManager.shouldIntercept("https://cdn.example.com/img/anim.gif"))

        // Não deve interceptar rotas de API, WebSockets ou páginas HTML normais
        assertFalse(RocketMediaCacheManager.shouldIntercept("https://chat.example.com/home"))
        assertFalse(RocketMediaCacheManager.shouldIntercept("https://chat.example.com/channel/general"))
        assertFalse(RocketMediaCacheManager.shouldIntercept("https://chat.example.com/api/v1/chat.postMessage"))
        assertFalse(RocketMediaCacheManager.shouldIntercept("wss://chat.example.com/websocket"))
        assertFalse(RocketMediaCacheManager.shouldIntercept("javascript:void(0)"))
    }
}
