package com.authorss81.noteflow.services

/**
 * Single source of truth for the in-memory data encryption key (DEK).
 *
 * The DEK is both the SQLCipher database passphrase and the field-encryption
 * key. It is only present in memory while the vault is unlocked; it is zeroized
 * on lock. The lazy Room helper factory reads it at first database open, which
 * is why it must live outside both the ViewModel and the database singleton.
 */
object VaultKeyHolder {
    @Volatile
    var dek: ByteArray? = null

    fun zeroize() {
        dek?.fill(0.toByte())
        dek = null
    }
}
