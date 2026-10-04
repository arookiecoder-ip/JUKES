package com.example.juke.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/** Render the server's qrcode SvgImage rectangles with native Canvas. */
@Composable fun AlexaJamQr(svg: String) {
    val modules = remember(svg) { runCatching {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(svg))
        val rectangles = mutableListOf<List<Float>>()
        var dimension = 1f
        fun number(name: String) = parser.getAttributeValue(null,name).orEmpty().removeSuffix("mm").toFloatOrNull() ?: 0f
        while(parser.eventType != XmlPullParser.END_DOCUMENT) {
            if(parser.eventType == XmlPullParser.START_TAG) {
                if(parser.name == "svg") dimension = number("width").coerceAtLeast(1f)
                if(parser.name == "rect") rectangles.add(listOf(number("x"),number("y"),number("width"),number("height")))
            }
            parser.next()
        }
        dimension to rectangles.toList()
    }.getOrDefault(1f to emptyList()) }
    Canvas(Modifier.size(240.dp).background(Color.White)) {
        val scale = size.width / modules.first
        modules.second.forEach { rect -> drawRect(Color.Black, Offset(rect[0]*scale, rect[1]*scale), Size(rect[2]*scale,rect[3]*scale)) }
    }
}
