package com.tatsuya.idtool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                IdToolScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdToolScreen() {
    var brand by remember { mutableStateOf(Brand.RELIER) }
    var storeName by remember { mutableStateOf("") }
    var storeNumber by remember { mutableStateOf("") }
    val context = LocalContext.current

    val rows = buildRows(brand, storeNumber)
    val valid = isValidStoreNumber(storeNumber)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("店舗IDツール") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // ブランド選択
            Text(
                "ブランドを選択",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Brand.entries.forEach { b ->
                    FilterChip(
                        selected = brand == b,
                        onClick = { brand = b },
                        label = { Text(b.label) }
                    )
                }
            }

            OutlinedTextField(
                value = storeName,
                onValueChange = { storeName = it },
                label = { Text("店名（空欄で可）") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )

            OutlinedTextField(
                value = storeNumber,
                onValueChange = { input ->
                    storeNumber = input.filter { it.isDigit() }.take(5)
                },
                label = { Text("店舗番号 5桁（中央の 67200 部分）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = storeNumber.isNotEmpty() && !valid,
                supportingText = {
                    Text(
                        if (valid || storeNumber.isEmpty()) "規格と Ch設定値 は固定。番号を入れると下の表が自動計算されます。"
                        else "5桁の数字を入力してください（残り ${5 - storeNumber.length} 桁）"
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            TableHeader()
            HorizontalDivider()

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(rows) { row ->
                    TableRow(row = row, onClick = {
                        if (row.fullId.isNotEmpty()) copyToClipboard(context, row.fullId)
                    })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderCell("ch", 0.18f)
        HeaderCell("ID（規格+Ch設定値+番号）", 0.62f)
        HeaderCell("CD", 0.20f)
    }
}

@Composable
private fun TableRow(row: IdRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = row.fullId.isNotEmpty(), onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BodyCell(row.ch.toString(), 0.18f)
        // 9桁部分（未入力なら規格+Ch設定値だけグレー表示）
        val idText = if (row.nineDigits.isNotEmpty()) row.nineDigits
        else "${row.kikaku}${row.chCode}-----"
        Text(
            text = idText,
            modifier = Modifier.weight(0.62f),
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
            color = if (row.nineDigits.isEmpty())
                MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = row.cd.ifEmpty { "—" },
            modifier = Modifier
                .weight(0.20f)
                .background(
                    if (row.cd.isNotEmpty()) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surface
                )
                .padding(vertical = 4.dp),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BodyCell(text: String, weight: Float) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        fontSize = 15.sp,
        textAlign = TextAlign.Center
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ID", text))
    Toast.makeText(context, "コピー: $text", Toast.LENGTH_SHORT).show()
}
