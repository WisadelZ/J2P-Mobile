/*
 * Copyright (C) 2026 WisadelZ
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.j2pmobile.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 账号凭据的本地加密（Android Keystore）。
 *
 * 账号文件（`account.dat`）由 Python 侧写入，但**加密用的是这里的系统密钥库密钥**：
 *
 * - 密钥在 Android Keystore 内生成（AES-256-GCM），**不可导出**，进程只能请求它加解密；
 *   密文落盘在应用私有目录，因此「账号密码加密存储在本地」不依赖任何写在源码里的常量
 *   （开源仓库里看到的代码也推不出密钥）。
 * - 每次加密使用随机 IV（`setRandomizedEncryptionRequired(true)`），IV 前置在密文里；
 *   GCM 带认证标签，密文被改动会解密失败。
 * - 换机 / 清除应用数据后密钥随之消失，旧密文自然解不开（符合「绑定本机安装」的语义）。
 *
 * 跨语言约定：Python 侧只传 **base64 字符串**（明文进、密文出；密文进、明文出），
 * 编解码与异常都在本类内处理，失败一律返回空串，由调用方回退或提示。
 */
object AccountCrypto {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "j2p_mobile_account_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    /** base64(明文) -> base64(IV + 密文)；失败返回空串。 */
    @JvmStatic
    fun encryptB64(plainBase64: String): String = try {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.doFinal(Base64.decode(plainBase64, Base64.NO_WRAP))
        Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
    } catch (_: Throwable) {
        ""
    }

    /** base64(IV + 密文) -> base64(明文)；密钥不符或密文被改时返回空串。 */
    @JvmStatic
    fun decryptB64(tokenBase64: String): String = try {
        val raw = Base64.decode(tokenBase64, Base64.NO_WRAP)
        if (raw.size <= IV_LENGTH) {
            ""
        } else {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, raw, 0, IV_LENGTH),
            )
            Base64.encodeToString(
                cipher.doFinal(raw, IV_LENGTH, raw.size - IV_LENGTH),
                Base64.NO_WRAP,
            )
        }
    } catch (_: Throwable) {
        ""
    }

    /** 取（必要时生成）系统密钥库里的账号密钥；密钥不存在时按固定参数生成。 */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}