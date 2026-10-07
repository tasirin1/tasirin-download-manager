package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertEquals
import org.junit.Test

class NotifIdWrapTest {
    @Test
    fun `wrap naik normal dan bungkus di batas`() {
        assertEquals(10001, NotificationHelper.wrapNotifId(10000))
        assertEquals(
            NotificationHelper.NOTIF_ID_BASE,
            NotificationHelper.wrapNotifId(NotificationHelper.NOTIF_ID_MAX)
        )
        assertEquals(
            NotificationHelper.NOTIF_ID_BASE,
            NotificationHelper.wrapNotifId(Int.MAX_VALUE)
        )
    }
}
