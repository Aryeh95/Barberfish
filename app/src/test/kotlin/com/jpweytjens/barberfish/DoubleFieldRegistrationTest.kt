package com.jpweytjens.barberfish

import com.jpweytjens.barberfish.extension.DoubleFieldKind
import com.jpweytjens.barberfish.extension.barberfishDataTypes
import io.hammerhead.karooext.KarooSystemService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The Double count lives in Settings.kt; the picker entries in extension_info.xml are static and
 * must follow it. Checks every Double is registered, listed with its name, and in the same order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoubleFieldRegistrationTest {

    @Test
    fun `extension_info lists every registered field in order, each Double with its name`() {
        val context = RuntimeEnvironment.getApplication()
        val parser = context.resources.getXml(R.xml.extension_info)
        val listed = mutableListOf<Pair<String, String>>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "DataType") {
                val typeId = parser.getAttributeValue(null, "typeId")
                val nameRes = parser.getAttributeResourceValue(null, "displayName", 0)
                listed += typeId to if (nameRes != 0) context.getString(nameRes) else ""
            }
        }
        val registered = barberfishDataTypes(KarooSystemService(context)).map { it.typeId }
        assertEquals(registered, listed.map { it.first })
        val doubles = listed.filter { it.first.startsWith("double-") }
        assertEquals(DoubleFieldKind.entries.map { it.typeId to it.title }, doubles)
    }
}
