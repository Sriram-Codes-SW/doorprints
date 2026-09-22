package com.househunt.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.househunt.app.R
import com.househunt.app.data.ChecklistLabels
import com.househunt.app.data.HouseEntity
import com.househunt.app.data.Repository
import com.househunt.app.data.labelRes
import com.househunt.shared.api.ApiException
import com.househunt.shared.api.HouseDraftDto
import com.househunt.shared.model.HouseStatus
import com.househunt.shared.model.MAX_PHOTOS_PER_HOUSE
import com.househunt.app.location.ReverseGeocoder
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HouseEditScreen(
    houseId: String?,
    newLat: Double?,
    newLon: Double?,
    visitId: String?,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val repo = repository()
    val scope = rememberCoroutineScope()
    val isNew = houseId == null
    val id = remember { houseId ?: UUID.randomUUID().toString() }
    val defaultLabel = stringResource(R.string.house_default_label)
    val streetLabel = stringResource(R.string.house_default_label_street)
    val unnamed = stringResource(R.string.house_unnamed)

    var draft by remember { mutableStateOf<HouseEntity?>(null) }
    LaunchedEffect(id) {
        if (draft != null) return@LaunchedEffect
        val existing = if (isNew) null else repo.getHouse(id)
        draft = existing ?: run {
            val now = System.currentTimeMillis()
            val lat = newLat ?: 0.0
            val lon = newLon ?: 0.0
            val place = ReverseGeocoder(context).lookup(lat, lon)
            HouseEntity(
                id = id, label = place?.street?.let { String.format(streetLabel, it) } ?: defaultLabel,
                address = place?.address, street = place?.street, locality = place?.locality,
                lat = lat, lon = lon, createdAt = now, updatedAt = now,
            )
        }
    }

    val saved by (if (isNew) flowOf(null) else repo.house(id)).collectAsStateWithLifecycle(null)
    val visits by repo.visitsFor(id).collectAsStateWithLifecycle(emptyList())
    val photos by repo.photosFor(id).collectAsStateWithLifecycle(emptyList())
    val aiEnabled by repo.aiEnabled.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val limitText = stringResource(R.string.house_photo_limit, MAX_PHOTOS_PER_HOUSE)
    val unreadableText = stringResource(R.string.house_photo_unreadable)

    fun save(andClose: Boolean = true) {
        val d = draft ?: return
        scope.launch {
            repo.saveHouse(d.copy(label = d.label.ifBlank { unnamed }))
            if (visitId != null) repo.getVisit(visitId)?.let { repo.saveVisit(it.copy(houseId = d.id)) }
            if (andClose) onDone()
        }
    }

    fun addPhoto(uri: Uri) = scope.launch {
        message = when (repo.addPhoto(id, uri)) {
            Repository.AddPhotoResult.ADDED -> null
            Repository.AddPhotoResult.LIMIT_REACHED -> limitText
            Repository.AddPhotoResult.UNREADABLE -> unreadableText
        }
    }

    // Camera capture goes to a temp file, then gets shrunk and stored like any picked photo.
    val cameraFile = remember { File(context.cacheDir, "camera/capture.jpg").apply { parentFile?.mkdirs() } }
    val cameraUri = remember {
        FileProvider.getUriForFile(context, context.packageName + ".files", cameraFile)
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) addPhoto(Uri.fromFile(cameraFile))
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) addPhoto(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (isNew) R.string.house_new_title else R.string.house_details_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.house_delete))
                    }
                    TextButton(onClick = { save() }) { Text(stringResource(R.string.common_save)) }
                },
            )
        },
    ) { padding ->
        val d = draft
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        fun update(transform: (HouseEntity) -> HouseEntity) { draft = transform(d) }

        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (aiEnabled) {
                OutlinedButton(onClick = { showPaste = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.house_paste_listing))
                }
            }
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            OutlinedTextField(d.label, { v -> update { it.copy(label = v) } },
                label = { Text(stringResource(R.string.house_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())

            SectionHeading(stringResource(R.string.house_status))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                HouseStatus.entries.forEachIndexed { i, s ->
                    SegmentedButton(
                        selected = d.status == s, onClick = { update { it.copy(status = s) } },
                        shape = SegmentedButtonDefaults.itemShape(i, HouseStatus.entries.size),
                    ) { Text(stringResource(s.labelRes)) }
                }
            }

            SectionHeading(stringResource(R.string.house_rating))
            RatingRow(d.rating) { star -> update { it.copy(rating = if (it.rating == star) null else star) } }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    d.price?.toString() ?: "", { v -> update { it.copy(price = v.filter(Char::isDigit).take(12).toLongOrNull()) } },
                    label = { Text(stringResource(if (d.priceType == "SALE") R.string.house_price_sale else R.string.house_price_rent)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    d.bedrooms?.toString() ?: "", { v -> update { it.copy(bedrooms = v.filter(Char::isDigit).take(2).toIntOrNull()) } },
                    label = { Text(stringResource(R.string.house_bhk)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.width(100.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.selectableGroup()) {
                FilterChip(d.priceType != "SALE", { update { it.copy(priceType = "RENT") } },
                    label = { Text(stringResource(R.string.house_rent)) })
                Spacer(Modifier.width(8.dp))
                FilterChip(d.priceType == "SALE", { update { it.copy(priceType = "SALE") } },
                    label = { Text(stringResource(R.string.house_buy)) })
            }

            OutlinedTextField(d.address ?: "", { v -> update { it.copy(address = v) } },
                label = { Text(stringResource(R.string.house_address)) }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(d.street ?: "", { v -> update { it.copy(street = v.ifBlank { null }) } },
                    label = { Text(stringResource(R.string.house_street)) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(d.locality ?: "", { v -> update { it.copy(locality = v.ifBlank { null }) } },
                    label = { Text(stringResource(R.string.house_locality)) }, singleLine = true, modifier = Modifier.weight(1f))
            }

            SectionHeading(stringResource(R.string.house_checklist))
            ChecklistLabels.items.forEach { (key, labelRes) ->
                ChecklistRow(stringResource(labelRes), d.checklist[key]) { n ->
                    update {
                        val current = it.checklist[key]
                        it.copy(checklist = if (current == n) it.checklist - key else it.checklist + (key to n))
                    }
                }
            }
            Text(stringResource(R.string.house_overall, d.score.scoreText()), fontWeight = FontWeight.SemiBold)

            OutlinedTextField(d.notes ?: "", { v -> update { it.copy(notes = v) } },
                label = { Text(stringResource(R.string.house_notes)) }, minLines = 3, modifier = Modifier.fillMaxWidth())

            SectionHeading(stringResource(R.string.house_contact))
            OutlinedTextField(d.contactName ?: "", { v -> update { it.copy(contactName = v) } },
                label = { Text(stringResource(R.string.house_contact_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(d.contactPhone ?: "", { v -> update { it.copy(contactPhone = v) } },
                    label = { Text(stringResource(R.string.house_phone)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true,
                    modifier = Modifier.weight(1f))
                if (!d.contactPhone.isNullOrBlank()) {
                    val callDesc = stringResource(R.string.house_call_desc, d.contactPhone)
                    TextButton(
                        onClick = {
                            val digits = d.contactPhone.filter { it.isDigit() || it == '+' }
                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")))
                        },
                        modifier = Modifier.semantics { contentDescription = callDesc },
                    ) { Text(stringResource(R.string.house_call)) }
                }
            }
            OutlinedTextField(d.listingUrl ?: "", { v -> update { it.copy(listingUrl = v) } },
                label = { Text(stringResource(R.string.house_listing)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true,
                modifier = Modifier.fillMaxWidth())

            HorizontalDivider()
            SectionHeading(stringResource(R.string.house_photos))
            if (saved == null) {
                Text(stringResource(R.string.house_save_first_photos), style = MaterialTheme.typography.bodySmall)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { takePicture.launch(cameraUri) }) { Text(stringResource(R.string.house_take_photo)) }
                    OutlinedButton(onClick = {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text(stringResource(R.string.house_from_gallery)) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val name = d.label.ifBlank { unnamed }
                    photos.forEachIndexed { index, p ->
                        Box {
                            AsyncImage(
                                model = File(p.path),
                                contentDescription = stringResource(R.string.house_photo_desc, index + 1, name),
                                contentScale = ContentScale.Crop, modifier = Modifier.size(120.dp),
                            )
                            // IconButton is 48 dp, on a surface so it stays visible on light photos.
                            Surface(shape = MaterialTheme.shapes.small, tonalElevation = 2.dp,
                                modifier = Modifier.align(Alignment.TopEnd)) {
                                IconButton(onClick = { scope.launch { repo.deletePhoto(p) } }) {
                                    Icon(Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.house_delete_photo, index + 1))
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.house_visits, visits.size), fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() })
                if (saved != null) TextButton(onClick = { scope.launch { repo.markVisitedNow(d) } }) {
                    Text(stringResource(R.string.house_here_now))
                }
            }
            visits.forEach { v ->
                val source = stringResource(if (v.source.name == "AUTO") R.string.visit_auto else R.string.visit_manual)
                val text = v.leftAt?.let {
                    stringResource(R.string.visit_line_duration, v.arrivedAt.dateText(), ((it - v.arrivedAt) / 60_000).toInt(), source)
                } ?: stringResource(R.string.visit_line, v.arrivedAt.dateText(), source)
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.house_location, String.format(Locale.ROOT, "%.5f", d.lat),
                String.format(Locale.ROOT, "%.5f", d.lon)),
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_save)) }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.house_delete_confirm_title)) },
            text = { Text(stringResource(R.string.house_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { scope.launch { repo.deleteHouse(id); onDone() } }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (showPaste) {
        PasteListingDialog(
            onDismiss = { showPaste = false },
            onDraft = { draftFromAi, warnings ->
                draft = draft?.let { mergeDraft(it, draftFromAi) }
                message = if (warnings.isEmpty()) context.getString(R.string.house_paste_done)
                else context.getString(R.string.house_paste_warnings, warnings.joinToString("; "))
                showPaste = false
            },
        )
    }
}

/**
 * Copies the AI suggestion into the form. Only fields the listing actually provided are set; location, status,
 * rating and checklist stay as they are. Amenities are appended to the notes. Nothing is saved until "Save".
 */
private fun mergeDraft(h: HouseEntity, a: HouseDraftDto): HouseEntity {
    val extraNotes = listOfNotNull(a.notes?.takeIf { it.isNotBlank() },
        a.amenities.takeIf { it.isNotEmpty() }?.joinToString(", "))
    val notes = (listOfNotNull(h.notes?.takeIf { it.isNotBlank() }) + extraNotes).joinToString("\n").ifBlank { null }
    return h.copy(
        label = a.label?.takeIf { it.isNotBlank() } ?: h.label,
        address = a.address ?: h.address,
        street = a.street ?: h.street,
        locality = a.locality ?: h.locality,
        price = a.price ?: h.price,
        priceType = a.priceType ?: h.priceType,
        bedrooms = a.bedrooms ?: h.bedrooms,
        contactName = a.contactName ?: h.contactName,
        contactPhone = a.contactPhone ?: h.contactPhone,
        listingUrl = a.listingUrl ?: h.listingUrl,
        notes = notes,
    )
}

@Composable
private fun PasteListingDialog(onDismiss: () -> Unit, onDraft: (HouseDraftDto, List<String>) -> Unit) {
    val repo = repository()
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val errorText = aiErrorText()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.house_paste_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.house_paste_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    text, { text = it.take(8000) },
                    label = { Text(stringResource(R.string.house_paste_field)) },
                    minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.ai_disclosure), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.house_paste_busy), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && text.isNotBlank(), onClick = {
                scope.launch {
                    busy = true
                    error = null
                    runCatching { repo.extractListing(text) }
                        .onSuccess { onDraft(it, it.warnings) }
                        .onFailure { error = errorText(it) }
                    busy = false
                }
            }) { Text(stringResource(R.string.house_paste_go)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Returns a function that turns an AI call failure into a translated message (read in composition). */
@Composable
fun aiErrorText(): (Throwable) -> String {
    val rate = stringResource(R.string.ai_rate_limited)
    val down = stringResource(R.string.ai_provider_down)
    val offline = stringResource(R.string.ai_offline)
    val generic = stringResource(R.string.ai_error)
    return { e ->
        when {
            e is ApiException && e.kind == ApiException.Kind.RATE_LIMITED -> String.format(rate, e.retryAfterSeconds ?: 60L)
            e is ApiException && e.kind == ApiException.Kind.AI_UNAVAILABLE -> down
            e is ApiException -> String.format(generic, e.code)
            else -> offline
        }
    }
}

/** 1-5 stars as a radio group: TalkBack says "3 out of 5, selected, radio button, 3 of 5". Tap again to clear. */
@Composable
private fun RatingRow(rating: Int?, onPick: (Int) -> Unit) {
    val starColor = LocalHouseHuntColors.current.star
    val none = stringResource(R.string.house_no_rating)
    val current = rating?.let { stringResource(R.string.common_stars, it) } ?: none
    Row(Modifier.selectableGroup().semantics { stateDescription = current }) {
        (1..5).forEach { star ->
            val selected = rating == star
            val desc = stringResource(R.string.common_stars, star)
            Box(
                Modifier.size(48.dp)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(star) })
                    .semantics { contentDescription = desc },
                contentAlignment = Alignment.Center,
            ) {
                Text(if ((rating ?: 0) >= star) "★" else "☆", style = MaterialTheme.typography.headlineSmall, color = starColor)
            }
        }
    }
}

/** One checklist item: label + 0-5 as a radio group (A11Y-A05). Tap the selected score again to clear it. */
@Composable
private fun ChecklistRow(label: String, value: Int?, onPick: (Int) -> Unit) {
    val state = value?.let { stringResource(R.string.house_check_value, it) } ?: stringResource(R.string.house_not_rated)
    Column {
        Text("$label · $state", style = MaterialTheme.typography.bodyMedium)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            (0..5).forEach { n ->
                val desc = stringResource(R.string.house_check_option, label, n)
                FilterChip(
                    selected = value == n,
                    onClick = { onPick(n) },
                    label = { Text("$n") },
                    modifier = Modifier.semantics {
                        contentDescription = desc
                        role = Role.RadioButton
                    },
                )
            }
        }
    }
}
