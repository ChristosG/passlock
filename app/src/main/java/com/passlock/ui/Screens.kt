@file:OptIn(ExperimentalMaterial3Api::class)

package com.passlock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.passlock.VaultUiState
import com.passlock.VaultViewModel
import com.passlock.domain.Item
import com.passlock.domain.Vault

@Composable
fun PassLockRoot(vm: VaultViewModel) {
    when (val state = vm.ui) {
        is VaultUiState.Unlocked -> VaultScreen(
            vault = state.vault,
            onLock = vm::lock,
            onAdd = vm::addItem,
            onCopy = vm::copy,
        )
        else -> AuthScreen(
            isSetup = state is VaultUiState.Setup,
            busy = vm.busy,
            error = vm.error,
            onSubmit = vm::submitAuth,
        )
    }
}

@Composable
fun AuthScreen(isSetup: Boolean, busy: Boolean, error: String?, onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    val tooShort = isSetup && password.isNotEmpty() && password.length < 8
    val mismatch = isSetup && confirm.isNotEmpty() && confirm != password
    val canSubmit = !busy && password.isNotEmpty() &&
        (!isSetup || (password.length >= 8 && password == confirm))

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("🔐", fontSize = 56.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                "PassLock",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (isSetup) "Create your master password" else "Enter your master password",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Master password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = tooShort,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isSetup) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Confirm password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = mismatch,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val hint = when {
                error != null -> error
                tooShort -> "Use at least 8 characters"
                mismatch -> "Passwords don't match"
                else -> null
            }
            if (hint != null) {
                Spacer(Modifier.height(10.dp))
                Text(hint, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onSubmit(password) },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(if (isSetup) "Create vault" else "Unlock", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Offline · encrypted · no network",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun VaultScreen(
    vault: Vault,
    onLock: () -> Unit,
    onAdd: (String, String) -> Unit,
    onCopy: (String) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val revealed = remember { mutableStateMapOf<String, Boolean>() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("🔐 Vault", fontWeight = FontWeight.Bold) },
                actions = { TextButton(onClick = onLock) { Text("Lock") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAdd = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Text("+", fontSize = 26.sp) }
        },
    ) { padding ->
        if (vault.items.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("🗄️", fontSize = 48.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "No secrets yet",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text("Tap + to add your first secret", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vault.items, key = { it.id }) { item ->
                    ItemCard(
                        item = item,
                        isExpanded = expanded[item.id] == true,
                        onToggle = { expanded[item.id] = !(expanded[item.id] ?: false) },
                        revealed = revealed,
                        onReveal = { fid -> revealed[fid] = !(revealed[fid] ?: false) },
                        onCopy = onCopy,
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddItemDialog(
            onDismiss = { showAdd = false },
            onConfirm = { title, value ->
                onAdd(title, value)
                showAdd = false
            },
        )
    }
}

@Composable
private fun ItemCard(
    item: Item,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    revealed: Map<String, Boolean>,
    onReveal: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.title,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(if (isExpanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isExpanded) {
                Spacer(Modifier.height(8.dp))
                for (field in item.fields) {
                    val show = !field.isSecret || revealed[field.id] == true
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                field.label.uppercase(),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (show) field.value else "••••••",
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (field.isSecret) {
                            TextButton(onClick = { onReveal(field.id) }) {
                                Text(if (show) "Hide" else "Show")
                            }
                        }
                        TextButton(onClick = { onCopy(field.value) }) { Text("Copy") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddItemDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(title, value) }, enabled = value.isNotEmpty()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("New secret") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Secret value") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
