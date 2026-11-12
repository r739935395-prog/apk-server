package com.rafat.whatshub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rafat.whatshub.R
import com.rafat.whatshub.database.AccountStatus
import com.rafat.whatshub.ui.theme.BrandCarbonSurface
import com.rafat.whatshub.ui.theme.BrandRefinedTeal
import com.rafat.whatshub.ui.theme.BrandWarmGold
import com.rafat.whatshub.ui.theme.StatusErrorRed
import com.rafat.whatshub.ui.theme.StatusSuccessGreen
import com.rafat.whatshub.ui.theme.StatusWarningAmber
import com.rafat.whatshub.ui.theme.accountColor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun RafatHubMonogramMark(size: Dp = 36.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(11.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF132A2E), BrandCarbonSurface)
                )
            )
            .border(1.dp, BrandWarmGold.copy(alpha = 0.45f), RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "R",
                color = BrandRefinedTeal,
                fontWeight = FontWeight.ExtraBold,
                fontSize = (size.value * 0.42f).sp
            )
            Text(
                text = "H",
                color = Color(0xFFF8FAFC),
                fontWeight = FontWeight.ExtraBold,
                fontSize = (size.value * 0.42f).sp
            )
        }
    }
}

@Composable
fun AccountBadge(
    accountId: Int,
    label: String? = null,
    modifier: Modifier = Modifier
) {
    val color = accountColor(accountId)
    val text = label?.takeIf { it.isNotBlank() } ?: stringResource(R.string.account_slot_format, accountId)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
fun AccountStatusLabel(status: AccountStatus): String = when (status) {
    AccountStatus.NOT_CONNECTED -> stringResource(R.string.state_not_connected)
    AccountStatus.CONNECTING -> stringResource(R.string.state_connecting)
    AccountStatus.WAITING_FOR_QR -> stringResource(R.string.state_waiting_for_qr)
    AccountStatus.PAIRING -> stringResource(R.string.state_pairing)
    AccountStatus.CONNECTED -> stringResource(R.string.state_connected)
    AccountStatus.RECONNECTING -> stringResource(R.string.state_reconnecting)
    AccountStatus.DISCONNECTED -> stringResource(R.string.state_disconnected)
    AccountStatus.AUTH_EXPIRED -> stringResource(R.string.state_auth_expired)
    AccountStatus.CONNECTION_ERROR -> stringResource(R.string.state_connection_error)
}

@Composable
fun AccountStatusChip(status: AccountStatus, reconnectAttempt: Int = 0) {
    val (bg, fg) = when (status) {
        AccountStatus.CONNECTED ->
            StatusSuccessGreen.copy(alpha = 0.16f) to StatusSuccessGreen
        AccountStatus.CONNECTING, AccountStatus.WAITING_FOR_QR, AccountStatus.PAIRING, AccountStatus.RECONNECTING ->
            StatusWarningAmber.copy(alpha = 0.18f) to StatusWarningAmber
        AccountStatus.AUTH_EXPIRED, AccountStatus.CONNECTION_ERROR ->
            StatusErrorRed.copy(alpha = 0.18f) to StatusErrorRed
        AccountStatus.NOT_CONNECTED, AccountStatus.DISCONNECTED ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val base = AccountStatusLabel(status)
    val suffix = if (status == AccountStatus.RECONNECTING && reconnectAttempt > 0) " (#$reconnectAttempt)" else ""

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            text = base + suffix,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun ConversationAvatar(
    title: String,
    accountId: Int,
    isGroup: Boolean,
    size: Dp = 48.dp
) {
    val ringColor = accountColor(accountId)
    val cleanFirst = title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(2.dp, ringColor.copy(alpha = 0.9f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (isGroup) {
            Icon(
                imageVector = Icons.Default.Group,
                contentDescription = null,
                tint = ringColor,
                modifier = Modifier.size(size * 0.52f)
            )
        } else {
            Text(
                text = cleanFirst,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.37f).sp
            )
        }
    }
}

fun formatHubTimestamp(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = timestampMs }
    val sameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "dd/MM/yy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestampMs))
}

fun formatConversationDayHeader(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = timestampMs }
    if (now.get(Calendar.YEAR) == target.get(Calendar.YEAR)) {
        val dayDiff = now.get(Calendar.DAY_OF_YEAR) - target.get(Calendar.DAY_OF_YEAR)
        if (dayDiff == 0) return "Today"
        if (dayDiff == 1) return "Yesterday"
    }
    return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(timestampMs))
}
