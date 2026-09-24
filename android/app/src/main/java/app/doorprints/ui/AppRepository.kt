package app.doorprints.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.doorprints.DoorprintsApp
import app.doorprints.data.AndroidRepository

/**
 * The app's repository for the screens still in `:app` (the Map, the house form, Export and Import), which also use
 * its Android-only members (photo files, `addPhoto(Uri)`). The common screens read the [app.doorprints.data.Repository]
 * interface from [LocalAppServices] instead (CMP-5). Was in Root.kt, which moved to `:ui`.
 */
@Composable
fun repository(): AndroidRepository = (LocalContext.current.applicationContext as DoorprintsApp).container.repository
