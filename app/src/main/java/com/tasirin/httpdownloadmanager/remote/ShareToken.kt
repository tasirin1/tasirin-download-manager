package com.tasirin.httpdownloadmanager.remote

/** Token berbagi file sementara: id item + waktu kedaluwarsa. */
internal data class ShareEntry(val itemId: String, val expiresAt: Long)
