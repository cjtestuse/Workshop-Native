package com.slay.workshopnative.ui.feature.login

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.slay.workshopnative.R
import com.slay.workshopnative.data.model.AuthChallengeType
import com.slay.workshopnative.data.model.SessionStatus
import com.slay.workshopnative.data.model.SteamSessionState
import com.slay.workshopnative.data.preferences.SavedSteamAccount
import com.slay.workshopnative.ui.components.WorkshopBackdrop
import com.slay.workshopnative.ui.theme.workshopAdaptiveBorderColor
import com.slay.workshopnative.ui.theme.workshopAdaptiveSurfaceColor

@Composable
fun LoginScreen(
    sessionState: SteamSessionState,
    savedAccounts: List<SavedSteamAccount>,
    onLogin: (String, String, Boolean) -> Unit,
    onSubmitAuthCode: (String) -> Unit,
    onSwitchSavedAccount: (String) -> Unit,
    onContinueAsGuest: () -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var authCode by remember { mutableStateOf("") }
    var rememberSession by rememberSaveable { mutableStateOf(true) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(sessionState.account?.accountName) {
        if (username.isBlank()) {
            username = sessionState.account?.accountName.orEmpty()
        }
    }

    LaunchedEffect(sessionState.status, sessionState.challenge?.type, sessionState.challenge?.previousCodeIncorrect) {
        if (sessionState.status != SessionStatus.AwaitingCode) {
            authCode = ""
        }
    }

    val awaitingCode = sessionState.status == SessionStatus.AwaitingCode && sessionState.challenge != null
    val isBusy = sessionState.status == SessionStatus.Connecting || sessionState.status == SessionStatus.Authenticating
    val inputsEnabled = !awaitingCode &&
        sessionState.status != SessionStatus.Connecting &&
        sessionState.status != SessionStatus.Authenticating
    val busyLabel = when (sessionState.status) {
        SessionStatus.Connecting -> "正在连接 Steam…"
        SessionStatus.Authenticating -> "正在验证登录信息…"
        else -> "正在处理…"
    }

    WorkshopBackdrop {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 430.dp),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 50.dp),
                    shape = RoundedCornerShape(36.dp),
                    color = workshopAdaptiveSurfaceColor(
                        light = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        dark = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
                    ),
                    tonalElevation = 8.dp,
                    shadowElevation = 18.dp,
                    border = BorderStroke(
                        1.dp,
                        workshopAdaptiveBorderColor(
                            light = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f),
                        ),
                    ),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 22.dp, vertical = 24.dp)
                            .padding(top = 44.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = "Workshop Native",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )

                        sessionState.errorMessage?.let { message ->
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                            ) {
                                Text(
                                    text = message,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }

                        CredentialField(
                            value = username,
                            onValueChange = { username = it },
                            placeholder = "Steam 用户名",
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Person,
                                    contentDescription = null,
                                )
                            },
                            enabled = inputsEnabled,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Next,
                            ),
                        )

                        CredentialField(
                            value = password,
                            onValueChange = { password = it },
                            placeholder = "密码",
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = null,
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) {
                                            Icons.Rounded.VisibilityOff
                                        } else {
                                            Icons.Rounded.Visibility
                                        },
                                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                    )
                                }
                            },
                            enabled = inputsEnabled,
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (username.isNotBlank() && password.isNotBlank() && inputsEnabled) {
                                        onLogin(username, password, rememberSession)
                                    }
                                },
                            ),
                        )

                        RememberSessionRow(
                            checked = rememberSession,
                            onCheckedChange = { rememberSession = it },
                        )

                        if (awaitingCode) {
                            AuthCodeCard(
                                sessionState = sessionState,
                                authCode = authCode,
                                onAuthCodeChange = { authCode = it },
                                onSubmit = { onSubmitAuthCode(authCode) },
                            )
                        }

                        if (!awaitingCode) {
                            Button(
                                onClick = { onLogin(username, password, rememberSession) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(22.dp),
                                enabled = username.isNotBlank() &&
                                    password.isNotBlank() &&
                                    inputsEnabled,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            ) {
                                Text(
                                    text = "登录 Steam",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            OutlinedButton(
                                onClick = onContinueAsGuest,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                enabled = inputsEnabled,
                                shape = RoundedCornerShape(20.dp),
                            ) {
                                Text(
                                    text = "匿名访问",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            if (savedAccounts.isNotEmpty()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = "已保存账号",
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    savedAccounts.forEach { account ->
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(18.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            border = BorderStroke(
                                                1.dp,
                                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
                                            ),
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(
                                                    modifier = Modifier.weight(1f),
                                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                                ) {
                                                    Text(
                                                        text = account.accountName,
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.SemiBold,
                                                    )
                                                    Text(
                                                        text = if (account.steamId64 > 0L) {
                                                            "SteamID ${account.steamId64}"
                                                        } else {
                                                            "已保存登录态"
                                                        },
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                                OutlinedButton(
                                                    onClick = { onSwitchSavedAccount(account.stableKey()) },
                                                    shape = RoundedCornerShape(16.dp),
                                                    enabled = inputsEnabled,
                                                ) {
                                                    Text("切换进入")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                BrandMark(modifier = Modifier.align(Alignment.TopCenter))
            }

            if (isBusy) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = RoundedCornerShape(26.dp),
                    color = workshopAdaptiveSurfaceColor(
                        light = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        dark = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
                    ),
                    tonalElevation = 8.dp,
                    shadowElevation = 14.dp,
                    border = BorderStroke(
                        1.dp,
                        workshopAdaptiveBorderColor(
                            light = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                        ),
                    ),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                        Text(
                            text = busyLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandMark(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(30.dp),
        shadowElevation = 18.dp,
        tonalElevation = 8.dp,
        border = BorderStroke(1.dp, workshopAdaptiveBorderColor(light = Color.White.copy(alpha = 0.36f))),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(30.dp))
                .background(
                    brush = Brush.linearGradient(
                        listOf(
                            Color(0xFF162334),
                            Color(0xFFE96D43),
                        ),
                    ),
                )
                .padding(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFFF8EEE5),
                shadowElevation = 6.dp,
            ) {
                Box(
                    modifier = Modifier
                        .size(74.dp)
                        .background(Color(0xFFF8EEE5)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(58.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}

@Composable
private fun CredentialField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge,
        placeholder = {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.84f),
            )
        },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = RoundedCornerShape(22.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = workshopAdaptiveSurfaceColor(
                light = MaterialTheme.colorScheme.surfaceContainerLowest,
                dark = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
            ),
            focusedContainerColor = workshopAdaptiveSurfaceColor(
                light = MaterialTheme.colorScheme.surfaceContainerLowest,
                dark = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
            ),
            disabledContainerColor = workshopAdaptiveSurfaceColor(
                light = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.72f),
                dark = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
            ),
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            unfocusedBorderColor = workshopAdaptiveBorderColor(
                light = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            ),
            disabledBorderColor = workshopAdaptiveBorderColor(
                light = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            ),
            focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
            unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedTrailingIconColor = MaterialTheme.colorScheme.primary,
            unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun RememberSessionRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "记住登录状态",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.surface,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            )
        }
    }
}

@Composable
private fun AuthCodeCard(
    sessionState: SteamSessionState,
    authCode: String,
    onAuthCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val challenge = sessionState.challenge ?: return
    val title = when (challenge.type) {
        AuthChallengeType.SteamGuard -> "Steam Guard"
        AuthChallengeType.Email -> challenge.emailHint?.let { "邮箱验证码 · $it" } ?: "邮箱验证码"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (challenge.previousCodeIncorrect) {
                Text(
                    text = "验证码不正确，请重新输入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            CredentialField(
                value = authCode,
                onValueChange = onAuthCodeChange,
                placeholder = "验证码",
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (authCode.isNotBlank()) onSubmit() }),
            )

            Button(
                onClick = onSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(20.dp),
                enabled = authCode.isNotBlank(),
            ) {
                Text("验证并登录", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
