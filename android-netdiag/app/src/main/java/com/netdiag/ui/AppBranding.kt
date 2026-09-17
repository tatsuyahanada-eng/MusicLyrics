package com.netdiag.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netdiag.R

/**
 * The app glyph — the same green-neon globe/network artwork used for the
 * launcher icon (see res/drawable-xxxhdpi/ic_launcher_foreground.png), shown
 * full-bleed here since the in-app header isn't clipped by an OS icon mask.
 */
@Composable
fun NetScopeLogo(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Image(
        painter = painterResource(R.drawable.netscope_logo),
        contentDescription = "NetScope",
        modifier = modifier.size(size),
    )
}

/**
 * App identity block for the TOP screen: logo + "NetScope" wordmark. Kept
 * separate from the terminal top bar so the brand mark shows once, prominently,
 * on the home tab rather than in every section header.
 */
@Composable
fun NetScopeHeader(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetScopeLogo(size = 52.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "NetScope",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "ネットワーク調査ツール",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
