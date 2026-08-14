package com.balancesentinel.app.data.network

import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NetworkSecurityConfigPinParityTest {

    @Test
    fun `xml pins match Kotlin policy and are scoped only to api deepseek com`() {
        val parser = ApplicationProvider.getApplicationContext<android.content.Context>()
            .resources
            .getXml(R.xml.network_security_config)
        val pins = mutableListOf<String>()
        var domainCount = 0
        var event = parser.next()
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name) {
                    "domain" -> {
                        domainCount++
                        assertEquals("false", parser.getAttributeValue(null, "includeSubdomains"))
                        assertEquals(DeepSeekTlsPolicy.HOST, parser.nextText())
                    }
                    "pin" -> {
                        assertEquals("SHA-256", parser.getAttributeValue(null, "digest"))
                        pins += "sha256/${parser.nextText()}"
                    }
                }
            }
            event = parser.next()
        }

        assertEquals(1, domainCount)
        assertEquals(DeepSeekTlsPolicy.pins.toSet(), pins.toSet())
        assertEquals(2, pins.distinct().size)
        assertTrue(pins.all { it.removePrefix("sha256/").length == 44 })
    }

    @Test
    fun `manifest exposes network state for diagnostics`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS
        )

        assertTrue(
            packageInfo.requestedPermissions.orEmpty().contains(
                "android.permission.ACCESS_NETWORK_STATE"
            )
        )
    }
}
