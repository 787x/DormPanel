package com.dormpanel.app.schedule

/** Candidate credential resolution shared by account save and all network entry points.
 * Paths do not define an authentication identity; origin, username and exact TLS trust do.
 * No secret is inherited until this rule has passed. Fully supplied accounts keep the
 * existing transport's origin/TLS checks and encrypted persistence unchanged. */
object WebDavCredentials {
    /** Compare values without exposing secrets through WebDavAccount.toString(). */
    fun sameConfiguration(a: WebDavAccount?, b: WebDavAccount?): Boolean =
        a != null && b != null && a.baseUrl.trim() == b.baseUrl.trim() && a.username == b.username &&
            a.password == b.password && a.certificatePem == b.certificatePem
    private fun sameOrigin(a: String, b: String): Boolean {
        val client=WebDavClient();val x=client.url(a);val y=client.url(b)
        return x.scheme==y.scheme && x.host==y.host && x.port==y.port
    }
    fun inheritedCertificate(previous: WebDavAccount?, baseUrl: String, username: String): String? =
        previous?.takeIf { sameOrigin(it.baseUrl,baseUrl) && it.username==username }?.certificatePem

    fun candidate(previous: WebDavAccount?, baseUrl: String, username: String,
                  enteredPassword: String, certificatePem: String?): WebDavAccount {
        WebDavClient().url(baseUrl)
        val trust=certificatePem?.takeIf(String::isNotBlank)?.let(WebDavTls::fingerprint)
        val password=if(enteredPassword.isNotEmpty()) enteredPassword else {
            require(previous!=null && sameOrigin(previous.baseUrl,baseUrl) && previous.username==username &&
                previous.certificatePem?.takeIf(String::isNotBlank)?.let(WebDavTls::fingerprint)==trust) {
                "服务器、用户名或证书信任已变化，请重新输入密码"
            }
            previous.password
        }
        require(username.isNotEmpty() && password.isNotEmpty()) { "请输入 WebDAV 用户名和密码" }
        return WebDavAccount(baseUrl,username,password,certificatePem?.takeIf(String::isNotBlank))
    }
}
