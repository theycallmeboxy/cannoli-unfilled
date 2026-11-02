package dev.cannoli.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R
import dev.cannoli.ui.theme.LocalCannoliColors

@Composable
fun MissingCoreDialog(
    coreName: String,
    emulatorName: String,
    buttonStyle: ButtonStyle = ButtonStyle()
) {
    OverlayScrim(
        bottomBar = { LegendPill(button = buttonStyle.back, label = stringResource(R.string.label_close)) }
    ) {
        Text(
            text = stringResource(R.string.dialog_title_missing_core),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.dialog_missing_core_pkg, coreName, emulatorName) + "\n" +
                stringResource(R.string.dialog_missing_core_install_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = LocalCannoliColors.current.text.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun MissingAppDialog(
    appName: String,
    buttonStyle: ButtonStyle = ButtonStyle()
) {
    OverlayScrim(
        bottomBar = { LegendPill(button = buttonStyle.back, label = stringResource(R.string.label_close)) }
    ) {
        Text(
            text = stringResource(R.string.dialog_title_missing_app),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.dialog_missing_app, appName),
            style = MaterialTheme.typography.bodyLarge,
            color = LocalCannoliColors.current.text.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun LaunchErrorDialog(message: String, buttonStyle: ButtonStyle = ButtonStyle()) {
    OverlayScrim(
        bottomBar = { LegendPill(button = buttonStyle.back, label = stringResource(R.string.label_close)) }
    ) {
        Text(
            text = stringResource(R.string.dialog_title_launch_error),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = LocalCannoliColors.current.text.copy(alpha = 0.6f)
        )
    }
}
