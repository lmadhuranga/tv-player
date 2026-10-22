package dev.lmadhuranga.tvplayer

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.FileInputStream
import java.net.ServerSocket
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * Per-TV HTTPS for the LAN handoff. A provisioned server must never fall back to HTTP:
 * if a certificate is missing, invalid, expired, or no longer matches the TV IP,
 * startup fails with a visible message instead.
 *
 * Files are provisioned in the app-private directory by scripts/https-tv.sh.
 * Neither a certificate private key nor its password is committed to source control.
 */
internal object LanHttps {
    private const val ENABLED = "lan-https.enabled"
    private const val KEYSTORE = "lan-https.p12"
    private const val PASSWORD = "lan-https.pass"

    fun isConfigured(context: Context): Boolean = File(context.filesDir, ENABLED).isFile

    @Throws(IOException::class)
    fun unboundListener(context: Context, ipAddress: String): ServerSocket {
        if (!isConfigured(context)) return ServerSocket()
        val keyStoreFile = File(context.filesDir, KEYSTORE)
        val passwordFile = File(context.filesDir, PASSWORD)
        if (!keyStoreFile.isFile || !passwordFile.isFile) {
            throw IOException("HTTPS was enabled but its private certificate is missing. Run scripts/https-tv.sh again.")
        }

        val passphrase = passwordFile.readText().trimEnd('\r', '\n').toCharArray()
        if (passphrase.isEmpty()) throw IOException("The HTTPS certificate password is missing.")

        try {
            val keyStore = KeyStore.getInstance("PKCS12")
            FileInputStream(keyStoreFile).use { input -> keyStore.load(input, passphrase) }
            val aliases = keyStore.aliases()
            var serverAlias: String? = null
            while (aliases.hasMoreElements()) {
                val candidate = aliases.nextElement()
                if (keyStore.isKeyEntry(candidate)) {
                    serverAlias = candidate
                    break
                }
            }
            val alias = serverAlias ?: throw IOException("HTTPS certificate has no private key.")
            val certificate = keyStore.getCertificate(alias) as? X509Certificate
                ?: throw IOException("HTTPS certificate is invalid.")
            certificate.checkValidity()
            val matchesIp = certificate.subjectAlternativeNames?.any { entry ->
                entry.size >= 2 && (entry[0] as? Int) == 7 && entry[1].toString() == ipAddress
            } ?: false
            if (!matchesIp) {
                throw IOException("HTTPS certificate does not match TV address $ipAddress. Run scripts/https-tv.sh again.")
            }

            val managerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            managerFactory.init(keyStore, passphrase)
            val tls = SSLContext.getInstance("TLS")
            tls.init(managerFactory.keyManagers, null, null)
            val listener = tls.serverSocketFactory.createServerSocket() as SSLServerSocket
            val allowed = listener.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
            if (allowed.isEmpty()) {
                listener.close()
                throw IOException("The TV does not support TLS 1.2 or newer.")
            }
            listener.enabledProtocols = allowed.toTypedArray()
            return listener
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("HTTPS could not load its certificate: ${error.javaClass.simpleName}. Reprovision it.", error)
        } finally {
            passphrase.fill('\u0000')
        }
    }
}
