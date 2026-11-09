package com.example.chikko

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 and later draw the app under the status and navigation bars. This picks bar
        // icons that stay readable, and safeDrawingPadding keeps the content clear of the bars.
        enableEdgeToEdge()
        setContent {
            Box(Modifier.safeDrawingPadding()) { Greeting("World") }
        }
    }
}

@Composable
fun Greeting(name: String) {
    Text(text = "Hello, " + name + "!")
}

// Press the Preview button in the editor toolbar to render these through the Compose interpreter.
// showBackground gives them the white an app window has; without it the text sits on the canvas.
@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    Greeting("Compose")
}

@Preview(showBackground = true)
@Composable
fun CardPreview() {
    Column {
        Text("Title")
        Text("Body")
    }
}
