package minifeiq

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import minifeiq.ui.MiniFeiQApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "MiniFeiQ (Kotlin)",
        state = rememberWindowState(width = 720.dp, height = 520.dp)
    ) {
        MiniFeiQApp()
    }
}
