package com.tline.phone

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.tline.phone.CustomElements.AlertDialog
import com.tline.phone.CustomElements.verticalScrollbar

fun NavGraphBuilder.accountsScreenRoute(navController: NavController) {
    composable("accounts") { AccountsScreen(navController) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(navController: NavController) {
    val showNewAccount = remember { mutableStateOf(BaresipService.uas.value.isEmpty()) }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                Spacer(Modifier.statusBarsPadding())
                TopAppBar(
                    title = { Text(text = stringResource(R.string.accounts), fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                    navigationIcon = {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                            )
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )
            }
        },
        floatingActionButton = {
            if (!showNewAccount.value && BaresipService.uas.value.isNotEmpty()) {
                SmallFloatingActionButton(
                    onClick = { showNewAccount.value = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_account))
                }
            }
        },
        content = { contentPadding ->
            if (showNewAccount.value || BaresipService.uas.value.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .verticalScroll(rememberScrollState())
                ) {
                    NewAccount(navController)
                }
            } else {
                AccountsContent(contentPadding, navController)
            }
        },
    )
}

@Composable
fun AccountsContent(contentPadding: PaddingValues, navController: NavController) {

    val showDialog = remember { mutableStateOf(false) }
    val message = remember { mutableStateOf("") }
    val lastAction = remember { mutableStateOf({}) }

    AlertDialog(
        showDialog = showDialog,
        title = stringResource(R.string.confirmation),
        message = message.value,
        firstButtonText = stringResource(R.string.cancel),
        lastButtonText = stringResource(R.string.delete),
        onLastClicked = lastAction.value,
    )

    val showAccounts = remember { mutableStateOf(true) }

    if (showAccounts.value && BaresipService.uas.value.isNotEmpty()) {

        val lazyListState = rememberLazyListState()

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                .verticalScrollbar(state = lazyListState),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(BaresipService.uas.value) { ua ->
                val account = ua.account
                val aor = account.aor
                val text = account.text()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = text,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp)
                            .clickable { navController.navigate("account/$aor/old") }
                    )
                    val deleteAccountMessage = stringResource(R.string.delete_account)
                    SmallFloatingActionButton(
                        modifier = Modifier.padding(end = 8.dp),
                        onClick = {
                            message.value = String.format(deleteAccountMessage, text)
                            lastAction.value = {
                                CallHistoryNew.clear(aor)
                                Message.clearMessagesOfAor(aor)
                                Blocked.clear(aor)
                                BlockRule.clear(aor)
                                ua.remove()
                                Api.ua_destroy(ua.uap)
                                Account.saveAccounts()
                                showAccounts.value = false
                                showAccounts.value = true
                            }
                            showDialog.value = true
                        },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.delete)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NewAccount(navController: NavController) {

    val alertTitle = remember { mutableStateOf("") }
    val alertMessage = remember { mutableStateOf("") }
    val showAlert = remember { mutableStateOf(false) }

    AlertDialog(
        showDialog = showAlert,
        title = alertTitle.value,
        message = alertMessage.value,
        lastButtonText = stringResource(R.string.ok),
    )

    fun createNew(ctx: Context, newAor: String, authUser: String, authPass: String, register: Boolean): Account? {
        val aor = if (newAor.startsWith("sip:")) newAor else "sip:$newAor"

        if (!Utils.checkAor(aor)) {
            alertTitle.value = ctx.getString(R.string.notice)
            alertMessage.value = String.format(ctx.getString(R.string.invalid_aor), aor.substringAfter(":"))
            showAlert.value = true
            return null
        }
        if (Account.ofAor(aor) != null) {
            alertTitle.value = ctx.getString(R.string.notice)
            alertMessage.value = String.format(ctx.getString(R.string.account_exists), aor.substringAfter(":"))
            showAlert.value = true
            return null
        }

        val uap = UserAgent.uaAlloc(
            "<$aor>;stunserver=\"stun:stun.l.google.com:19302\";regq=0.5;pubint=0;regint=0;check_origin=no;mwi=no"
        )
        if (uap == 0L) {
            alertTitle.value = ctx.getString(R.string.notice)
            alertMessage.value = ctx.getString(R.string.account_allocation_failure)
            showAlert.value = true
            return null
        }

        val ua = UserAgent(uap)
        val acc = ua.account
        val login = authUser.trim()
        var setupOk = true
        if (login.isNotEmpty() && Api.account_set_auth_user(acc.accp, login) != 0) setupOk = false
        if (authPass.isNotEmpty() && Api.account_set_auth_pass(acc.accp, authPass) != 0) setupOk = false
        if (Api.account_set_regint(acc.accp, if (register) REGISTRATION_INTERVAL else 0) != 0) setupOk = false

        if (!setupOk) {
            Api.ua_destroy(uap)
            alertTitle.value = ctx.getString(R.string.notice)
            alertMessage.value = ctx.getString(R.string.account_allocation_failure)
            showAlert.value = true
            return null
        }

        acc.authUser = Api.account_auth_user(acc.accp)
        acc.authPass = Api.account_auth_pass(acc.accp).ifEmpty { NO_AUTH_PASS }
        acc.regint = Api.account_regint(acc.accp)
        acc.configuredRegInt = REGISTRATION_INTERVAL
        acc.checkOrigin = true
        ua.add()
        Account.saveAccounts()
        if (register) ua.reRegister()
        Log.d(TAG, "Quick setup created ${acc.aor}, registration=${acc.regint > 0}")
        return acc
    }

    var newAor by remember { mutableStateOf("") }
    var authUser by remember { mutableStateOf("") }
    var authUserEdited by remember { mutableStateOf(false) }
    var authPass by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(true) }
    var showPassword by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val ctx = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.quick_sip_setup),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.quick_sip_setup_hint),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = newAor,
                onValueChange = { value ->
                    newAor = value
                    if (!authUserEdited)
                        authUser = value.removePrefix("sip:").substringBefore("@").trim()
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.sip_uri)) },
                placeholder = { Text("user@example.com") },
                textStyle = TextStyle(fontSize = 17.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            OutlinedTextField(
                value = authUser,
                onValueChange = { authUser = it; authUserEdited = true },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.authentication_username)) },
                textStyle = TextStyle(fontSize = 17.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            OutlinedTextField(
                value = authPass,
                onValueChange = { authPass = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.authentication_password)) },
                textStyle = TextStyle(fontSize = 17.sp),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = null
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.register),
                    modifier = Modifier.weight(1f),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Switch(checked = register, onCheckedChange = { register = it })
            }
            Button(
                onClick = {
                    val account = createNew(ctx, newAor.trim(), authUser, authPass, register)
                    if (account != null) {
                        newAor = ""
                        authUser = ""
                        authPass = ""
                        focusManager.clearFocus()
                        navController.navigateUp()
                    }
                },
                enabled = newAor.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.add_and_connect), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
