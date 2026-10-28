@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.llembas.android.ui.signin

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.llembas.android.R
import eu.llembas.android.api.Doorstep
import eu.llembas.android.api.ServerFit
import eu.llembas.android.api.fit
import eu.llembas.android.api.normaliseBase
import eu.llembas.android.lembas
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the sign-in is. The token arrives once, by device code, as `lembas login` gets it. */
sealed interface SignInState {
    data object Address : SignInState
    data object Finding : SignInState
    data class Code(val userCode: String, val verify: String) : SignInState
    data class Failed(val message: String) : SignInState
    data object Done : SignInState
}

/** The sentence for a server this app will not work with, or null when it fits. */
fun refusal(fit: ServerFit): Int? = when (fit) {
    ServerFit.FITS -> null
    ServerFit.NOT_LEMBAS, ServerFit.TOO_OLD -> R.string.signin_too_old
    ServerFit.TOO_NEW -> R.string.signin_too_new
}

class SignInViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<SignInState>(SignInState.Address)
    val state = _state.asStateFlow()
    private var polling: Job? = null

    fun start(typed: String) {
        polling?.cancel()
        polling = viewModelScope.launch {
            _state.value = SignInState.Finding
            val doorstep = Doorstep()
            try {
                val base = normaliseBase(typed)
                val found = doorstep.discover(base)
                refusal(fit(found))?.let { said ->
                    _state.value = SignInState.Failed(getApplication<Application>().getString(said))
                    return@launch
                }
                val device = getApplication<Application>().getString(R.string.device_name)
                val code = doorstep.deviceCode(found.login.device.code, device, "${Build.MANUFACTURER} ${Build.MODEL}")
                _state.value = SignInState.Code(code.userCode, code.verificationUriComplete.ifEmpty { code.verificationUri })
                var interval = code.interval.coerceAtLeast(2) * 1000L
                val until = System.currentTimeMillis() + code.expiresIn * 1000L
                while (System.currentTimeMillis() < until) {
                    delay(interval)
                    val answer = doorstep.deviceToken(found.login.device.token, code.deviceCode)
                    when {
                        answer.accessToken != null -> {
                            val account = answer.account
                            getApplication<Application>().lembas.instances.add(
                                base = found.baseUrl.ifEmpty { base }.trimEnd('/'),
                                name = found.name,
                                email = account?.email.orEmpty(),
                                token = answer.accessToken,
                            )
                            _state.value = SignInState.Done
                            return@launch
                        }

                        answer.error == "slow_down" -> interval += 5000L

                        answer.error == "authorization_pending" -> Unit

                        answer.error == "access_denied" -> {
                            _state.value = SignInState.Failed(getApplication<Application>().getString(R.string.signin_denied))
                            return@launch
                        }

                        else -> {
                            _state.value = SignInState.Failed(getApplication<Application>().getString(R.string.signin_expired))
                            return@launch
                        }
                    }
                }
                _state.value = SignInState.Failed(getApplication<Application>().getString(R.string.signin_expired))
            } catch (e: Exception) {
                _state.value = SignInState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun reset() {
        polling?.cancel()
        _state.value = SignInState.Address
    }
}

@Composable
fun SignInScreen(onDone: () -> Unit = {}, model: SignInViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) { if (state == SignInState.Done) onDone() }
    var address by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.signin_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        when (val s = state) {
            SignInState.Address, is SignInState.Failed -> {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.signin_address)) },
                    placeholder = { Text("llm.example.org") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank()) model.start(address) }),
                    modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
                )
                if (s is SignInState.Failed) {
                    Text(s.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.widthIn(max = 420.dp))
                }
                Button(onClick = { model.start(address) }, enabled = address.isNotBlank()) {
                    Text(stringResource(R.string.signin_continue))
                }
            }

            SignInState.Finding, SignInState.Done -> CircularProgressIndicator()

            is SignInState.Code -> {
                Text(stringResource(R.string.signin_code_intro), modifier = Modifier.widthIn(max = 420.dp))
                Text(s.userCode, fontFamily = FontFamily.Monospace, fontSize = 32.sp, style = MaterialTheme.typography.headlineLarge)
                Button(onClick = { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(s.verify)) }) {
                    Text(stringResource(R.string.signin_open_browser))
                }
                CircularProgressIndicator()
                OutlinedButton(onClick = { model.reset() }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}
