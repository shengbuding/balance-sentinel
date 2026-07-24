package com.balancesentinel.app.data.console.auth

/**
 * 控制台登录提供者接口
 */
interface ConsoleAuthProvider {
    val providerId: String
    val displayName: String
    suspend fun isLoggedIn(): Boolean
    suspend fun getSession(): ConsoleSession?
    suspend fun saveSession(cookies: Map<String, String>, email: String?)
    suspend fun logout()
}
