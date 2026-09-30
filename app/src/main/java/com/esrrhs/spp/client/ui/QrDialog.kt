package com.esrrhs.spp.client.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.esrrhs.spp.client.util.ProfileShare
import com.esrrhs.spp.client.util.QrCodes
import com.esrrhs.spp.client.spp.Profile

/** 展示某配置的分享二维码，支持把分享文本发送给其它应用。 */
@Composable
fun QrShareDialog(
    profile: Profile,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val payload = ProfileShare.encode(profile)
    val bitmap = QrCodes.toBitmap(payload)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分享配置 · ${profile.name}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "QR code",
                    modifier = Modifier.size(260.dp),
                )
                Text(
                    text = "用其它设备扫码添加，或点右侧发送文本",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, payload)
                }
                context.startActivity(Intent.createChooser(send, "分享配置"))
            }) { Text("发送") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
