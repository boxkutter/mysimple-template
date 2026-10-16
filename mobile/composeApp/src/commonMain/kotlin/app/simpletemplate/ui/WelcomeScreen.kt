package app.simpletemplate.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.simpletemplate.shared.auth.AuthRepository
import app.simpletemplate.ui.resources.Res
import app.simpletemplate.ui.resources.app_logo
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.painterResource

/**
 * The first start: no account needed. The user gives their name and their organisation and starts;
 * everything stays on this phone. Signing in to a platform account is optional.
 */
@Composable
fun WelcomeScreen(auth: AuthRepository, deviceName: String?) {
    var signIn by remember { mutableStateOf(false) }
    if (signIn) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
            LoginScreen(auth, deviceName)
            TextButton(onClick = { signIn = false }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Back: start without an account") }
        }
        return
    }
    var name by remember { mutableStateOf("") }
    var organisation by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Image(painterResource(Res.drawable.app_logo), contentDescription = null, modifier = Modifier.size(88.dp))
        Text(APP_NAME, style = MaterialTheme.typography.headlineMedium)
        // One sentence on what the app is for, then the promise every app of the platform makes.
        Text(
            "A starting point for a new app. No account is needed: everything stays on this phone, encrypted.",
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedTextField(
            name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            organisation, { organisation = it }, label = { Text("Your organisation") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Your firm or employer. Leave it empty if you work on your own.") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        )
        Text(
            "Once you store information, the app is locked with this phone's own fingerprint, face or PIN, to protect it.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { auth.startLocal(name, organisation, TimeZone.currentSystemDefault().id) },
            enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Start", style = MaterialTheme.typography.titleMedium) }
        TextButton(onClick = { signIn = true }) { Text("I have an account: sign in") }
    }
}
