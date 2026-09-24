package app.doorprints.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.data.HouseEntity

/**
 * The Compare tab over the app's repository: the screen itself is common code in `:ui` (CMP-4 P4c); this collects its
 * two lists with the activity's lifecycle, as the screen did before it moved, until the lifecycle library is common
 * too (CMP-5). The file is not called `CompareScreen.kt`: both would compile to the JVM class
 * `app.doorprints.ui.CompareScreenKt`, and one module's would hide the other's.
 */
@Composable
fun CompareScreen(onOpenHouse: (String) -> Unit, onOpenMap: () -> Unit = {}) {
    val repo = repository()
    // null until Room answers, so the empty state does not flash on the way in.
    val loaded: List<HouseEntity>? by repo.houses.collectAsStateWithLifecycle(initialValue = null)
    val counts by repo.visitCounts.collectAsStateWithLifecycle(emptyList())
    CompareScreen(loaded, counts, onOpenHouse, onOpenMap)
}
