package com.saveory.frontwidget

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saveory.frontwidget.data.WeatherStatus

/**
 * TEMPORARY debug gallery: renders every canonical WeatherStatus with its day and night icon so
 * the whole set can be reviewed on-device. Launched from MainActivity via the "show_icon_grid"
 * intent extra. Delete this file (and its MainActivity hook) once the art is approved.
 */
@Composable
fun WeatherIconGallery() {
    val context = LocalContext.current
    fun idFor(key: String, day: Boolean): Int = context.resources.getIdentifier(
        "wx_${key}_${if (day) "day" else "night"}", "drawable", context.packageName
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFFFFFF))
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        MaterialSymbolsSamples()
        Text(
            text = "Weather icons - day / night",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        WeatherStatus.entries.forEach { status ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Image(
                    painter = painterResource(idFor(status.key, true)),
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFEFF1F4))
                        .padding(4.dp)
                )
                Image(
                    painter = painterResource(idFor(status.key, false)),
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF1EEFA))
                        .padding(4.dp)
                )
                Column {
                    Text(text = status.label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text = status.key, fontSize = 11.sp, color = Color(0xFF8A8F98),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.size(24.dp))
    }
}

/**
 * PROTOTYPE gallery: every canonical WeatherStatus as [current day | current night || new day |
 * new night], so the proposed Material Symbols set (msw_*, from tools/gen_ms_weather.py) can be
 * reviewed against the shipping art. Launched from MainActivity via the "show_msw_grid" extra.
 */
@Composable
private fun IconCell(res: Int, bg: Color) {
    Image(
        painter = painterResource(res),
        contentDescription = null,
        modifier = Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(bg).padding(4.dp)
    )
}

@Composable
fun MaterialWeatherGallery() {
    val context = LocalContext.current
    fun id(prefix: String, key: String, day: Boolean): Int = context.resources.getIdentifier(
        "${prefix}_${key}_${if (day) "day" else "night"}", "drawable", context.packageName
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        Text("Current  vs  Material Symbols", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text("wx day | wx night    ‖    msw day | msw night",
            fontSize = 11.sp, color = Color(0xFF8A8F98), modifier = Modifier.padding(bottom = 8.dp))
        WeatherStatus.entries.forEach { status ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconCell(id("wx", status.key, true), Color(0xFFEFF1F4))
                IconCell(id("wx", status.key, false), Color(0xFFF1EEFA))
                Spacer(Modifier.size(6.dp))
                IconCell(id("msw", status.key, true), Color(0xFFEFF1F4))
                IconCell(id("msw", status.key, false), Color(0xFFF1EEFA))
                Text(text = status.label, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp))
            }
        }
        Spacer(Modifier.size(24.dp))
    }
}

/**
 * PROTOTYPE preview: the current full-color weather art (left) next to the proposed Material Symbols
 * (Rounded), tinted per condition (right), for six representative scenarios. Delete with the rest of
 * this debug file once a direction is chosen.
 */
@Composable
private fun MaterialSymbolsSamples() {
    data class Sample(val label: String, val oldRes: Int, val newRes: Int)
    val samples = listOf(
        Sample("Clear - day", R.drawable.wx_clear_day, R.drawable.ms_sunny),
        Sample("Clear - night", R.drawable.wx_clear_night, R.drawable.ms_bedtime),
        Sample("Partly cloudy", R.drawable.wx_partly_cloudy_day, R.drawable.ms_partly_cloudy_day),
        Sample("Rain", R.drawable.wx_light_rain_day, R.drawable.ms_rainy),
        Sample("Snow", R.drawable.wx_snow_day, R.drawable.ms_weather_snowy),
        Sample("Thunderstorm", R.drawable.wx_thunderstorm_day, R.drawable.ms_thunderstorm),
    )
    Text(
        text = "Samples: current  vs  Material Symbols (Rounded)",
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Text(
        text = "left = current full-color    right = Material Symbols, tinted per condition",
        fontSize = 12.sp,
        color = Color(0xFF8A8F98),
        modifier = Modifier.padding(bottom = 10.dp)
    )
    samples.forEach { s ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Current art on a widget-like near-white background.
            Image(
                painter = painterResource(s.oldRes),
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF6F5F1))
                    .padding(8.dp)
            )
            Text(text = "\u2192", fontSize = 20.sp, color = Color(0xFFB0B4BB))
            Image(
                painter = painterResource(s.newRes),
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF6F5F1))
                    .padding(8.dp)
            )
            Text(
                text = s.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    Spacer(Modifier.size(16.dp))
    Text(
        text = "Merged two-tone (Material Symbols stacked, per-part color)",
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Text(
        text = "single-tint  vs  merged two-tone",
        fontSize = 12.sp,
        color = Color(0xFF8A8F98),
        modifier = Modifier.padding(bottom = 10.dp)
    )
    val merged = listOf(
        Triple("Partly cloudy", R.drawable.ms_partly_cloudy_day, R.drawable.ms2_partly_cloudy),
        Triple("Rain", R.drawable.ms_rainy, R.drawable.ms2_rain),
        Triple("Thunderstorm", R.drawable.ms_thunderstorm, R.drawable.ms2_thunderstorm),
    )
    merged.forEach { (label, singleRes, mergedRes) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Image(
                painter = painterResource(singleRes),
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF6F5F1))
                    .padding(8.dp)
            )
            Text(text = "\u2192", fontSize = 20.sp, color = Color(0xFFB0B4BB))
            Image(
                painter = painterResource(mergedRes),
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF6F5F1))
                    .padding(8.dp)
            )
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    Spacer(Modifier.size(20.dp))
}

/**
 * PROTOTYPE popup: the proposed final weather icon set (Material Symbols Rounded, merged two-tone
 * where a scenario has multiple parts) shown in a dialog card. Launched from MainActivity via the
 * "show_icon_popup" intent extra.
 */
@Composable
fun WeatherIconPopup(onDismiss: () -> Unit) {
    data class Row2(val label: String, val res: Int)
    val rows = listOf(
        Row2("Clear - day", R.drawable.ms_sunny),
        Row2("Clear - night", R.drawable.ms_bedtime),
        Row2("Partly cloudy", R.drawable.ms2_partly_cloudy),
        Row2("Rain", R.drawable.ms2_rain),
        Row2("Snow", R.drawable.ms2_snow),
        Row2("Thunderstorm", R.drawable.ms2_thunderstorm),
    )
    // Faint backdrop so the dialog reads as a popup over a screen.
    Box(Modifier.fillMaxSize().background(Color(0xFFF3EEE8)))
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color.White, tonalElevation = 8.dp) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text(
                    text = "Weather icons",
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp
                )
                Text(
                        text = "Material Symbols (Rounded), filled two-tone",
                    fontSize = 13.sp,
                    color = Color(0xFF8A8F98),
                    modifier = Modifier.padding(bottom = 14.dp)
                )
                rows.forEach { r ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Image(
                            painter = painterResource(r.res),
                            contentDescription = r.label,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFF6F5F1))
                                .padding(7.dp)
                        )
                        Text(
                            text = r.label,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
