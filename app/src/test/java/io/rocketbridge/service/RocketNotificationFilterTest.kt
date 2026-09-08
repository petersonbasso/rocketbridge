package io.rocketbridge.service

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RocketNotificationFilterTest {

    @Before
    fun setUp() {
        RocketWebSocketService.activeRoomId = null
        RocketWebSocketService.activeRoomPath = null
        RocketWebSocketService.isAppInForeground = false
    }

    @After
    fun tearDown() {
        RocketWebSocketService.activeRoomId = null
        RocketWebSocketService.activeRoomPath = null
        RocketWebSocketService.isAppInForeground = false
    }

    @Test
    fun testSameRoom_matchedByRid() {
        RocketWebSocketService.activeRoomId = "room123"
        RocketWebSocketService.activeRoomPath = "/channel/general"

        val isSame = RocketWebSocketService.isCurrentActiveRoom(
            rid = "room123",
            innerPayload = JSONObject().apply {
                put("name", "general")
                put("type", "c")
            },
            targetUrl = "https://chat.example.com/channel/general"
        )

        assertTrue("Deve identificar como mesma sala pelo rid", isSame)
    }

    @Test
    fun testDifferentRoom_byRid() {
        RocketWebSocketService.activeRoomId = "room123"
        RocketWebSocketService.activeRoomPath = "/channel/general"

        val isSame = RocketWebSocketService.isCurrentActiveRoom(
            rid = "room456",
            innerPayload = JSONObject().apply {
                put("name", "devs")
                put("type", "c")
            },
            targetUrl = "https://chat.example.com/channel/devs"
        )

        assertFalse("Deve identificar como sala diferente quando o rid e o canal não batem", isSame)
    }

    @Test
    fun testSameRoom_matchedByChannelPath() {
        RocketWebSocketService.activeRoomId = null
        RocketWebSocketService.activeRoomPath = "/channel/suporte"

        val isSame = RocketWebSocketService.isCurrentActiveRoom(
            rid = "roomSup99",
            innerPayload = JSONObject().apply {
                put("name", "suporte")
                put("type", "c")
            },
            targetUrl = "https://chat.example.com/channel/suporte"
        )

        assertTrue("Deve identificar como mesma sala pelo path do canal", isSame)
    }

    @Test
    fun testSameRoom_matchedByDirectMessage() {
        RocketWebSocketService.activeRoomId = null
        RocketWebSocketService.activeRoomPath = "/direct/maria"

        val isSame = RocketWebSocketService.isCurrentActiveRoom(
            rid = "directRoom123",
            innerPayload = JSONObject().apply {
                put("type", "d")
                put("sender", JSONObject().apply {
                    put("username", "maria")
                })
            },
            targetUrl = "https://chat.example.com/direct/maria"
        )

        assertTrue("Deve identificar como mesma conversa direta quando remetente coincide", isSame)
    }

    @Test
    fun testDifferentRoom_directMessageFromAnotherUser() {
        RocketWebSocketService.activeRoomId = null
        RocketWebSocketService.activeRoomPath = "/direct/maria"

        val isSame = RocketWebSocketService.isCurrentActiveRoom(
            rid = "directRoom456",
            innerPayload = JSONObject().apply {
                put("type", "d")
                put("sender", JSONObject().apply {
                    put("username", "carlos")
                })
            },
            targetUrl = "https://chat.example.com/direct/carlos"
        )

        assertFalse("Deve identificar como conversa diferente para outro remetente", isSame)
    }

    @Test
    fun testHomeScreen_noActiveRoom() {
        RocketWebSocketService.activeRoomId = null
        RocketWebSocketService.activeRoomPath = "/home"

        val isSame = RocketWebSocketService.isCurrentActiveRoom(
            rid = "roomAny",
            innerPayload = JSONObject().apply {
                put("name", "geral")
                put("type", "c")
            },
            targetUrl = "https://chat.example.com/channel/geral"
        )

        assertFalse("Na tela inicial (/home), qualquer mensagem deve ser tratada como sala diferente", isSame)
    }
}
