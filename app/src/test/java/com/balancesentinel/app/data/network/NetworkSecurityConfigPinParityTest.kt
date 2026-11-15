package com.balancesentinel.app.data.network

import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

/**
 * 平台层的 pin-set 曾经钉住 api.deepseek.com，也曾经是「关掉开关也不生效」的根因：
 * XML 里的 pin-set 是编译期常量，运行时的开关对它没有任何作用。
 *
 * 这两个测试把那个决定钉住：
 *  - XML 里不能再出现 pin-set，否则设置页的「强制 HTTPS」开关会再次变成摆设；
 *  - 明文禁令必须留在 base-config，且它不该被开关影响。
 */
@RunWith(RobolectricTestRunner::class)
class NetworkSecurityConfigPinParityTest {

    @Test
    fun `xml declares no platform pins so the runtime switch stays in control`() {
        var pinSetCount = 0
        var pinCount = 0
        var domainConfigCount = 0
        forEachStartTag { name, _ ->
            when (name) {
                "pin-set" -> pinSetCount++
                "pin" -> pinCount++
                "domain-config" -> domainConfigCount++
            }
        }

        assertEquals("pin-set must not come back to the XML", 0, pinSetCount)
        assertEquals(0, pinCount)
        assertEquals(0, domainConfigCount)
    }

    @Test
    fun `cleartext stays disabled for every host`() {
        val baseConfigCleartext = mutableListOf<String?>()
        forEachStartTag { name, attribute ->
            if (name == "base-config") baseConfigCleartext += attribute("cleartextTrafficPermitted")
        }

        assertEquals(listOf<String?>("false"), baseConfigCleartext)
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

    private fun forEachStartTag(onStart: (String, (String) -> String?) -> Unit) {
        val parser = ApplicationProvider.getApplicationContext<android.content.Context>()
            .resources
            .getXml(R.xml.network_security_config)
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                onStart(parser.name) { attribute -> parser.getAttributeValue(null, attribute) }
            }
            event = parser.next()
        }
    }
}
