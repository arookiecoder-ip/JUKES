package com.example.juke.network

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class CookieJarTest {
    private fun preferences(): SharedPreferences {
        val values = mutableMapOf<String, Set<String>>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putStringSet" -> { @Suppress("UNCHECKED_CAST") values[args[0] as String] = (args[1] as Set<String>).toSet(); editor }
                "apply" -> null
                "commit" -> true
                else -> editor
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getStringSet" -> values[args[0]] ?: args[1]
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
    }

    @Test fun stagingSessionPersistsWithoutLeakingToAnotherHostOrProductionPath() {
        val prefs = preferences()
        val login = "https://example.com/staging/login/".toHttpUrl()
        val cookie = Cookie.parse(login, "session=owner; Path=/staging; Secure; HttpOnly")!!
        PersistentCookieJar(prefs).saveFromResponse(login, listOf(cookie))
        val restored = PersistentCookieJar(prefs)
        assertEquals("owner", restored.loadForRequest("https://example.com/staging/api/home/".toHttpUrl()).single().value)
        assertTrue(restored.loadForRequest("https://example.com/api/home/".toHttpUrl()).isEmpty())
        assertTrue(restored.loadForRequest("https://other.com/staging/api/home/".toHttpUrl()).isEmpty())
        assertTrue(restored.loadForRequest("http://example.com/staging/api/home/".toHttpUrl()).isEmpty())
    }

    @Test fun serverCookieDeletionAndLocalSignOutPersistAcrossRestarts() {
        val prefs = preferences()
        val url = "https://example.com/login/".toHttpUrl()
        val jar = PersistentCookieJar(prefs)
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "session=owner; Path=/; Secure")!!))
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "session=; Path=/; Max-Age=0; Secure")!!))
        assertFalse(PersistentCookieJar(prefs).hasCookies())
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "session=owner; Path=/; Secure")!!))
        jar.clear()
        assertTrue(PersistentCookieJar(prefs).loadForRequest(url).isEmpty())
    }
}
