@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.passlock.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.passlock.VaultViewModel
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.passlock.data.AppSettings
import com.passlock.domain.Field
import com.passlock.domain.FieldType
import com.passlock.domain.Item
import com.passlock.domain.PasswordPolicy
import com.passlock.domain.Template
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private fun templateIcon(t: Template) = when (t) {
    Template.LOGIN -> "🔑"
    Template.CREDIT_CARD -> "💳"
    Template.BANK -> "🏦"
    Template.SECURE_NOTE -> "📝"
    Template.CUSTOM -> "🗂️"
}

private fun templateLabel(t: Template) = when (t) {
    Template.LOGIN -> "Login"
    Template.CREDIT_CARD -> "Credit card"
    Template.BANK -> "Bank"
    Template.SECURE_NOTE -> "Secure note"
    Template.CUSTOM -> "Custom"
}

/** Plain-language help for known field labels (shown via the (i) tooltip). */
private fun fieldHelp(label: String, type: FieldType): String? {
    if (type == FieldType.TOTP_SEED) {
        return "Paste the 2FA secret (Base32) or an otpauth:// URI — PassLock shows the rotating code."
    }
    return when (label.trim().lowercase()) {
        "card number" -> "The long number on the front of the card."
        "expiry" -> "The card's expiry date, e.g. 08/27."
        "cvv" -> "The 3-digit security code on the back of the card."
        "pin" -> "Your secret PIN — never share it."
        "cardholder" -> "The name printed on the card."
        "username" -> "Your login name or email for this account."
        "password" -> "Your login password. Tap 🎲 to generate a strong one."
        "url" -> "The website address for this login."
        "account" -> "Your bank account number."
        "iban" -> "International Bank Account Number."
        "note" -> "Any free-form secure note."
        else -> null
    }
}

private fun primaryField(item: Item): Field? =
    item.fields.firstOrNull { it.id == item.primaryFieldId } ?: item.fields.firstOrNull()

// ---------------------------------------------------------------- List

@Composable
fun VaultListScreen(vm: VaultViewModel, onEnableBiometric: (() -> Unit)?) {
    val items = vm.visibleItems()
    val scope = rememberCoroutineScope()
    var fabMenu by remember { mutableStateOf(false) }
    val pickGalleryImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { vm.addGalleryImage(uri) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("🔐 Vault", fontWeight = FontWeight.Bold) },
                actions = {
                    IconAction("🖼", "Gallery") { vm.openGallery() }
                    IconAction("⚙", "Settings") { vm.openSettings() }
                    IconAction("🔒", "Lock") { vm.lock() }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { fabMenu = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Text("+", fontSize = 26.sp) }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = vm.query.text,
                onValueChange = vm::setSearchText,
                placeholder = { Text("Search secrets…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            FilterRow(vm)
            if (onEnableBiometric != null) BiometricEnrollBanner(onEnable = onEnableBiometric)

            if (items.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("🗄️", fontSize = 48.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("No secrets here", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(6.dp))
                    Text("Tap + to add one", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items, key = { it.id }) { item -> ItemRow(vm, item) }
                }
            }
        }
        if (fabMenu) {
            OptionSheet(
                title = "Add",
                options = listOf(
                    SheetOption("New secret", description = "Create a login, card, note…", emoji = "🗂") { vm.openEditor(null) },
                    SheetOption("Add photo", description = "Encrypt a photo into your gallery", emoji = "🖼") { vm.expectActivityResult(); pickGalleryImage.launch("image/*") },
                ),
                onDismiss = { fabMenu = false },
            )
        }
    }
}

@Composable
private fun FilterRow(vm: VaultViewModel) {
    val q = vm.query
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = q.template == null && !q.favoritesOnly,
            onClick = { vm.clearFilters() },
            label = { Text("All") },
        )
        FilterChip(
            selected = q.favoritesOnly,
            onClick = { vm.showFavoritesOnly() },
            label = { Text("★ Favorites") },
        )
        for (t in listOf(Template.CREDIT_CARD, Template.LOGIN, Template.BANK, Template.SECURE_NOTE)) {
            FilterChip(
                selected = q.template == t,
                onClick = { vm.setTemplateFilter(t) },
                label = { Text(templateLabel(t)) },
            )
        }
    }
}

@Composable
private fun ItemRow(vm: VaultViewModel, item: Item) {
    val primary = primaryField(item)
    var menu by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { vm.openDetail(item.id) },
            onLongClick = { menu = true },
        ),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(templateIcon(item.template), fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title.ifBlank { "Untitled" }, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(templateLabel(item.template), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconAction(if (item.favorite) "★" else "☆", if (item.favorite) "Unfavorite" else "Favorite") { vm.toggleFavorite(item.id) }
            if (primary != null && primary.value.isNotEmpty()) {
                IconAction("⧉", "Copy ${primary.label}") {
                    val v = if (primary.type == FieldType.TOTP_SEED) vm.totpCode(primary.value) ?: primary.value else primary.value
                    vm.copy(v)
                }
            }
        }
    }
    if (menu) {
        OptionSheet(
            title = item.title.ifBlank { "Untitled" },
            options = listOf(
                SheetOption("Edit", emoji = "✏️") { vm.openEditor(item.id) },
                SheetOption(
                    label = if (item.favorite) "Remove from favorites" else "Add to favorites",
                    emoji = if (item.favorite) "★" else "☆",
                    selected = item.favorite,
                ) { vm.toggleFavorite(item.id) },
                SheetOption("Delete", emoji = "🗑", destructive = true) { vm.deleteItem(item.id) },
            ),
            onDismiss = { menu = false },
        )
    }
}

// ---------------------------------------------------------------- Gallery

@Composable
fun GalleryScreen(vm: VaultViewModel) {
    val ids = vm.allImageIds()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var viewerStart by remember { mutableStateOf<Int?>(null) }
    var actionId by remember { mutableStateOf<String?>(null) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { vm.addGalleryImage(uri) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Gallery (${ids.size})", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back") { vm.back() } },
                actions = { IconAction("➕", "Add photo") { vm.expectActivityResult(); pickImage.launch("image/*") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
    ) { padding ->
        if (ids.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("🖼", fontSize = 48.sp)
                Spacer(Modifier.height(12.dp))
                Text("No photos yet", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(6.dp))
                Text("Tap ➕ to add a photo, or attach images to items.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(ids) { index, id ->
                    EncryptedImage(
                        vm = vm,
                        id = id,
                        modifier = Modifier.aspectRatio(1f).combinedClickable(
                            onClick = { viewerStart = index },
                            onLongClick = { actionId = id },
                        ),
                    )
                }
            }
        }
    }

    viewerStart?.let { start -> ImageViewer(vm, ids, start) { viewerStart = null } }

    actionId?.let { id ->
        OptionSheet(
            title = "Photo",
            options = buildList {
                add(
                    SheetOption(
                        label = "Save to phone gallery",
                        emoji = "⬇️",
                        description = "Exports a plaintext copy to Pictures/PassLock — only do this if you're ok with it leaving the vault.",
                    ) {
                        scope.launch {
                            val ok = vm.saveImageToPhoneGallery(id)
                            Toast.makeText(context, if (ok) "Saved to Pictures/PassLock ✓" else "Couldn't save", Toast.LENGTH_LONG).show()
                        }
                    },
                )
                if (vm.isStandaloneImage(id)) {
                    add(SheetOption("Delete photo", emoji = "🗑", destructive = true) { vm.deleteGalleryImage(id) })
                }
            },
            onDismiss = { actionId = null },
        )
    }
}

// ---------------------------------------------------------------- Detail

@Composable
fun ItemDetailScreen(vm: VaultViewModel, itemId: String) {
    val item = vm.itemById(itemId)
    if (item == null) {
        LaunchedEffect(itemId) { vm.openList() }
        return
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var viewerStart by remember { mutableStateOf<Int?>(null) }
    val revealed = remember { mutableStateMapOf<String, Boolean>() }
    val activity = LocalContext.current as? FragmentActivity

    // 1-second ticker for live TOTP codes.
    var nowSec by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            nowSec = System.currentTimeMillis() / 1000
            delay(1000)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(item.title.ifBlank { "Untitled" }, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back") { vm.back() } },
                actions = {
                    IconAction(if (item.favorite) "★" else "☆", "Favorite") { vm.toggleFavorite(item.id) }
                    IconAction("✏", "Edit") { vm.openEditor(item.id) }
                    IconAction("🗑", "Delete") { confirmDelete = true }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
        ) {
            Text("${templateIcon(item.template)}  ${templateLabel(item.template)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(6.dp))
            for (field in item.fields) {
                FieldViewRow(
                    field = field,
                    revealed = revealed[field.id] == true,
                    onReveal = {
                        val currently = revealed[field.id] == true
                        if (!currently && field.requireBiometric && field.isSecret && activity != null) {
                            biometricGate(activity) { revealed[field.id] = true }
                        } else {
                            revealed[field.id] = !currently
                        }
                    },
                    nowSec = nowSec,
                    totp = { vm.totpCode(field.value, nowSec) },
                    onCopy = { value ->
                        if (field.requireBiometric && field.isSecret && activity != null) {
                            biometricGate(activity) { vm.copy(value) }
                        } else {
                            vm.copy(value)
                        }
                    },
                )
            }
            if (item.attachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Images (${item.attachments.size})", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(6.dp))
                val cols = 3
                item.attachments.chunked(cols).forEachIndexed { rowIdx, rowIds ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowIds.forEachIndexed { colIdx, att ->
                            val index = rowIdx * cols + colIdx
                            EncryptedImage(vm, att, Modifier.weight(1f).aspectRatio(1f).clickable { viewerStart = index })
                        }
                        repeat(cols - rowIds.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (item.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Tags: ${item.tags.joinToString(", ")}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(6.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteItem(item.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            title = { Text("Delete this secret?") },
            text = { Text("This permanently removes \"${item.title.ifBlank { "Untitled" }}\".") },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    viewerStart?.let { start ->
        ImageViewer(vm, item.attachments, start) { viewerStart = null }
    }
}

@Composable
private fun FieldViewRow(
    field: Field,
    revealed: Boolean,
    onReveal: () -> Unit,
    nowSec: Long,
    totp: () -> String?,
    onCopy: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(field.label.uppercase(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (field.type == FieldType.TOTP_SEED) {
                    val code = totp()
                    val remaining = 30 - (nowSec % 30)
                    Text(
                        if (code != null) code.chunked(3).joinToString(" ") else "invalid seed",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text("refreshes in ${remaining}s", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val show = !field.isSecret || revealed
                    Text(if (show) field.value else "••••••", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (field.isSecret && field.type != FieldType.TOTP_SEED) {
                IconAction(if (revealed) "🙈" else "👁", if (revealed) "Hide" else "Show") { onReveal() }
            }
            IconAction("⧉", "Copy") {
                val v = if (field.type == FieldType.TOTP_SEED) totp() ?: "" else field.value
                if (v.isNotEmpty()) onCopy(v)
            }
        }
    }
}

// ---------------------------------------------------------------- Editor

private class EditableField(
    val id: String,
    label: String,
    value: String,
    isSecret: Boolean,
    type: FieldType,
    requireBiometric: Boolean = false,
) {
    var label by mutableStateOf(label)
    var value by mutableStateOf(value)
    var isSecret by mutableStateOf(isSecret)
    var type by mutableStateOf(type)
    var requireBiometric by mutableStateOf(requireBiometric)
}

@Composable
fun ItemEditorScreen(vm: VaultViewModel, itemId: String?) {
    val existing = itemId?.let { vm.itemById(it) }
    val isNew = existing == null

    var template by remember { mutableStateOf(existing?.template ?: Template.CUSTOM) }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var favorite by remember { mutableStateOf(existing?.favorite ?: false) }
    var tagsText by remember { mutableStateOf(existing?.tags?.joinToString(", ") ?: "") }
    val fields = remember {
        mutableStateListOf<EditableField>().apply {
            val init = existing?.fields ?: vm.blankItem(template).fields
            init.forEach { add(EditableField(it.id, it.label, it.value, it.isSecret, it.type, it.requireBiometric)) }
        }
    }
    var primaryId by remember { mutableStateOf(existing?.primaryFieldId ?: fields.firstOrNull()?.id) }
    val scope = rememberCoroutineScope()
    val attachments = remember { mutableStateListOf<String>().apply { existing?.attachments?.let { addAll(it) } } }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { vm.encryptAndStoreImage(uri)?.let { attachments.add(it) } }
    }

    fun applyTemplate(t: Template) {
        template = t
        fields.clear()
        vm.blankItem(t).fields.forEach { fields.add(EditableField(it.id, it.label, it.value, it.isSecret, it.type, it.requireBiometric)) }
        primaryId = fields.firstOrNull { it.isSecret }?.id ?: fields.firstOrNull()?.id
    }

    fun save() {
        val built = fields.map { Field(it.id, it.label.ifBlank { "Field" }, it.value, it.type, it.isSecret, it.requireBiometric) }
        vm.saveItem(
            Item(
                id = existing?.id ?: UUID.randomUUID().toString(),
                title = title,
                template = template,
                fields = built,
                tags = tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                favorite = favorite,
                primaryFieldId = primaryId?.takeIf { id -> built.any { it.id == id } } ?: built.firstOrNull()?.id,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                attachments = attachments.toList(),
            ),
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New secret" else "Edit", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Cancel") { vm.back() } },
                actions = { IconAction("✓", "Save") { save() } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (isNew) {
                Text("Template", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (t in Template.entries) {
                        FilterChip(
                            selected = template == t,
                            onClick = { applyTemplate(t) },
                            label = { Text("${templateIcon(t)} ${templateLabel(t)}") },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Fields", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                InfoTip("Everything here is encrypted. \"Hidden\", \"Password\" and \"PIN\" just keep the value masked on screen — they don't change how it's secured.")
            }
            for (ef in fields) {
                FieldEditCard(
                    field = ef,
                    isPrimary = primaryId == ef.id,
                    onSetPrimary = { primaryId = ef.id },
                    onGenerate = {
                        ef.value = if (ef.type == FieldType.PIN) {
                            vm.generatePassword(PasswordPolicy(length = 6, lower = false, upper = false, digits = true, symbols = false))
                        } else {
                            vm.generatePassword()
                        }
                    },
                    onRemove = {
                        fields.remove(ef)
                        if (primaryId == ef.id) primaryId = fields.firstOrNull()?.id
                    },
                )
            }

            TextButton(onClick = { fields.add(EditableField(UUID.randomUUID().toString(), "", "", false, FieldType.TEXT)) }) {
                Text("+ Add field")
            }

            Text("Images", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (attachments.isNotEmpty()) {
                val cols = 3
                attachments.toList().chunked(cols).forEach { rowIds ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowIds.forEach { att ->
                            Box(Modifier.weight(1f).aspectRatio(1f)) {
                                EncryptedImage(vm, att, Modifier.fillMaxSize())
                                Box(Modifier.align(Alignment.TopEnd)) {
                                    IconAction("✕", "Remove image", tint = MaterialTheme.colorScheme.error) {
                                        attachments.remove(att)
                                        vm.deleteImageBlob(att)
                                    }
                                }
                            }
                        }
                        repeat(cols - rowIds.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            TextButton(onClick = { vm.expectActivityResult(); pickImage.launch("image/*") }) { Text("+ Add image") }

            OutlinedTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                label = { Text("Tags (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!isNew) {
                TextButton(onClick = { vm.deleteItem(existing!!.id) }) {
                    Text("Delete secret", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun FieldEditCard(
    field: EditableField,
    isPrimary: Boolean,
    onSetPrimary: () -> Unit,
    onGenerate: () -> Unit,
    onRemove: () -> Unit,
) {
    var reveal by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = field.label,
                onValueChange = { field.label = it },
                label = { Text("Label") },
                singleLine = true,
                trailingIcon = fieldHelp(field.label, field.type)?.let { help -> { InfoTip(help) } },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = field.value,
                onValueChange = { field.value = it },
                label = { Text(if (field.type == FieldType.TOTP_SEED) "Secret / otpauth URI" else "Value") },
                singleLine = field.type != FieldType.TOTP_SEED,
                visualTransformation = if (field.isSecret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (field.type == FieldType.PIN || field.type == FieldType.NUMBER) KeyboardType.Number else KeyboardType.Text,
                ),
                trailingIcon = if (field.isSecret) {
                    { Text(if (reveal) "🙈" else "👁", modifier = Modifier.clickable { reveal = !reveal }.padding(12.dp)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val kind = FieldKind.of(field.type, field.isSecret)
                KindSelector(kind) { k ->
                    if (field.label.isBlank()) field.label = k.label
                    field.type = k.type
                    field.isSecret = k.secret
                }
                InfoTip(kind.help)
                if (isPrimary) {
                    Spacer(Modifier.width(6.dp))
                    Text("★ quick-copy", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.weight(1f))
                if (field.type == FieldType.PASSWORD || field.type == FieldType.PIN) {
                    IconAction("🎲", "Generate") { onGenerate() }
                }
                FieldOverflowMenu(field = field, isPrimary = isPrimary, onSetPrimary = onSetPrimary, onRemove = onRemove)
            }
        }
    }
}

/** Friendly field "kinds" mapping to (type, secret) with plain-language help. */
private enum class FieldKind(val label: String, val type: FieldType, val secret: Boolean, val help: String) {
    TEXT("Text", FieldType.TEXT, false, "Plain text shown as-is — e.g. a username, email, or note."),
    HIDDEN("Hidden text", FieldType.TEXT, true, "Text kept masked until you tap reveal."),
    PASSWORD("Password", FieldType.PASSWORD, true, "A password — masked, with a 🎲 generator."),
    PIN("PIN", FieldType.PIN, true, "A numeric PIN — masked, number keyboard, generator."),
    NUMBER("Number", FieldType.NUMBER, false, "A number shown as-is — e.g. a card or account number."),
    TOTP("2FA code", FieldType.TOTP_SEED, false, "Paste a 2FA secret or otpauth:// URI; PassLock shows the rotating code.");

    companion object {
        fun of(type: FieldType, secret: Boolean): FieldKind = when (type) {
            FieldType.PASSWORD -> PASSWORD
            FieldType.PIN -> PIN
            FieldType.NUMBER -> NUMBER
            FieldType.TOTP_SEED -> TOTP
            FieldType.TEXT -> if (secret) HIDDEN else TEXT
        }
    }
}

@Composable
private fun KindSelector(current: FieldKind, onSelect: (FieldKind) -> Unit) {
    var sheet by remember { mutableStateOf(false) }
    TextButton(onClick = { sheet = true }) { Text("${current.label} ▾") }
    if (sheet) {
        OptionSheet(
            title = "Field kind",
            options = FieldKind.entries.map { k ->
                SheetOption(label = k.label, description = k.help, selected = k == current) { onSelect(k) }
            },
            onDismiss = { sheet = false },
        )
    }
}

@Composable
private fun FieldOverflowMenu(field: EditableField, isPrimary: Boolean, onSetPrimary: () -> Unit, onRemove: () -> Unit) {
    var sheet by remember { mutableStateOf(false) }
    IconAction("⋮", "More options") { sheet = true }
    if (sheet) {
        OptionSheet(
            title = null,
            options = buildList {
                add(
                    SheetOption(
                        label = if (isPrimary) "Quick-copy field" else "Set as quick-copy",
                        emoji = "⧉",
                        description = "The value copied from the home screen tap.",
                        selected = isPrimary,
                    ) { onSetPrimary() },
                )
                if (field.isSecret) {
                    add(
                        SheetOption(
                            label = if (field.requireBiometric) "Biometric reveal: ON" else "Require biometric to reveal",
                            emoji = "🔒",
                            selected = field.requireBiometric,
                        ) { field.requireBiometric = !field.requireBiometric },
                    )
                }
                add(SheetOption("Delete field", emoji = "🗑", destructive = true) { onRemove() })
            },
            onDismiss = { sheet = false },
        )
    }
}

@Composable
private fun BiometricEnrollBanner(onEnable: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Faster unlock", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("Use your fingerprint or face next time", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onEnable) { Text("Enable") }
        }
    }
}

@Composable
fun SettingsScreen(
    vm: VaultViewModel,
    onEnableBiometric: (() -> Unit)? = null,
    onDisableBiometric: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showExport by remember { mutableStateOf(false) }
    var showDuress by remember { mutableStateOf(false) }
    var pendingPass by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pass = pendingPass
        pendingPass = null
        if (uri != null && pass != null) {
            working = true
            scope.launch {
                val bytes = vm.exportBytes(pass.toCharArray())
                val ok = bytes != null && runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }.isSuccess
                working = false
                Toast.makeText(context, if (ok) "Encrypted backup saved ✓" else "Export failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back") { vm.back() } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Encrypted backup", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Export an encrypted .plk file protected by a separate recovery passphrase. " +
                    "Keep that passphrase safe — without it the backup can't be opened, and there's no recovery.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { showExport = true },
                enabled = !working,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text(if (working) "Exporting…" else "Export encrypted backup") }

            Spacer(Modifier.height(8.dp))
            Text("Appearance", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((mode, label) in listOf(
                    AppSettings.THEME_SYSTEM to "System",
                    AppSettings.THEME_DARK to "Dark",
                    AppSettings.THEME_LIGHT to "Light",
                )) {
                    FilterChip(selected = vm.themeMode == mode, onClick = { vm.chooseTheme(mode) }, label = { Text(label) })
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Security options", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = vm.autoWipeEnabled, onCheckedChange = { vm.chooseAutoWipe(it) })
                Column(Modifier.weight(1f)) {
                    Text(
                        "Auto-wipe after ${AppSettings.AUTO_WIPE_THRESHOLD} failed attempts",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                    )
                    Text("Permanently erases the vault. Off by default.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Duress / decoy password", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "A second password that opens a separate, empty decoy vault — for when you're forced to unlock. Your real vault stays hidden.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (vm.duressEnabled) {
                Text("✓ Duress password is set", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { vm.removeDuress() }) { Text("Remove duress password", color = MaterialTheme.colorScheme.error) }
            } else {
                Button(onClick = { showDuress = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Set up duress password") }
            }

            Spacer(Modifier.height(8.dp))
            Text("Unlock", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            when {
                !vm.biometricCapable -> Text("No biometrics enrolled on this device.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                vm.biometricEnrolled -> TextButton(onClick = onDisableBiometric) { Text("Disable biometric unlock") }
                onEnableBiometric != null -> Button(onClick = onEnableBiometric, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Enable biometric unlock") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = vm.requirePasswordColdStart, onCheckedChange = { vm.chooseRequirePassword(it) })
                Column(Modifier.weight(1f)) {
                    Text("Require master password at cold start", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                    Text("Biometrics only after a password unlock in the same session.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Text size — ${(vm.fontScale * 100).toInt()}%", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Slider(
                value = vm.fontScale,
                onValueChange = { vm.chooseFontScale(it) },
                valueRange = 0.85f..1.4f,
                steps = 10,
            )

            Spacer(Modifier.height(8.dp))
            Text("Security", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "• Offline — no network permission\n" +
                    "• AES-256-GCM + Argon2id\n" +
                    "• Hardware-wrapped key (StrongBox/TEE)\n" +
                    "• Screenshots blocked · auto-lock on background",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showExport) {
        ExportPassphraseDialog(
            onDismiss = { showExport = false },
            onConfirm = { pass ->
                showExport = false
                pendingPass = pass
                vm.expectActivityResult()
                createDoc.launch("passlock-backup.plk")
            },
        )
    }

    if (showDuress) {
        DuressDialog(
            onDismiss = { showDuress = false },
            onConfirm = { pass ->
                showDuress = false
                vm.setupDuress(pass.toCharArray())
            },
        )
    }
}

@Composable
private fun DuressDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val ok = pass.length >= 8 && pass == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(pass) }, enabled = ok) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Duress password") },
        text = {
            Column {
                Text(
                    "Entering this at unlock opens a separate, empty decoy vault. Make it different from your real password.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                PasswordField(value = pass, onValueChange = { pass = it }, label = "Duress password (8+)", modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                PasswordField(value = confirm, onValueChange = { confirm = it }, label = "Confirm", modifier = Modifier.fillMaxWidth())
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun ExportPassphraseDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val ok = pass.length >= 8 && pass == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(pass) }, enabled = ok) { Text("Choose file") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Recovery passphrase") },
        text = {
            Column {
                Text(
                    "Used only to encrypt this backup. Make it strong; you'll need it to restore.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                PasswordField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = "Recovery passphrase (8+)",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = "Confirm",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
