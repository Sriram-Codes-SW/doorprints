package app.doorprints.screenshots

import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.doorprints.crypto.DevicePlatform
import app.doorprints.drive.connect.BackupSummary
import app.doorprints.drive.connect.ConnectState
import app.doorprints.drive.connect.DeleteConfirmInfo
import app.doorprints.drive.connect.DeleteFactor
import app.doorprints.drive.connect.DriveReason
import app.doorprints.drive.connect.ListedDevice
import app.doorprints.drive.connect.SyncInfo
import app.doorprints.drive.connect.SyncState
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.photo.PhotoNetworkStatus
import app.doorprints.i18n.AppLocale
import app.doorprints.ui.DoorprintsTheme
import app.doorprints.ui.drive.BackupsUi
import app.doorprints.ui.drive.DeletePhase
import app.doorprints.ui.drive.DeleteUi
import app.doorprints.ui.drive.DevicesUi
import app.doorprints.ui.drive.DriveActions
import app.doorprints.ui.drive.DriveHolder
import app.doorprints.ui.drive.DrivePrompts
import app.doorprints.ui.drive.DriveSettingsContent
import app.doorprints.ui.drive.DriveUiState
import app.doorprints.ui.drive.EnrolUi
import app.doorprints.ui.drive.ListState
import app.doorprints.ui.drive.NewcomerOffer
import app.doorprints.ui.drive.ShownKey
import app.doorprints.ui.drive.SyncUi
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.drive_connect_heading
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy
import java.util.TimeZone

/**
 * Screenshots of the phones' Google Drive section (S4b-BL-116 to -119, -126): each main state, English in both themes and
 * Hindi light only, the same harness conventions as [ScreensScreenshotTest] (Robolectric native graphics, a tall window
 * cropped to the content, a wait for a known text before the capture). The state is given to
 * [DriveSettingsContent] as data, so nothing here touches Drive, Room or the network. Record with
 * `./gradlew :app:recordRoborazziDebug --tests 'app.doorprints.screenshots.DriveScreenshotTest'`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h4800dp-hdpi", application = ScreenshotTestApp::class)
class DriveScreenshotTest(private val lang: String, private val dark: Boolean) {
    @get:Rule val compose = createComposeRule()

    private val timeZone: TimeZone = TimeZone.getDefault()
    private val locales: LocaleList = LocaleList.getDefault()

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        RuntimeEnvironment.setQualifiers("+$lang" + if (dark) "-night" else "-notnight")
        AppLocale.applyDefault(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() {
        TimeZone.setDefault(timeZone)
        LocaleList.setDefault(locales)
    }

    /** A holder over actions that do nothing: the screens only call it from clicks, which these shots never make. */
    private val holder: DriveHolder by lazy {
        val state = MutableStateFlow(ConnectState.DISCONNECTED)
        val notice = MutableStateFlow<DriveReason?>(null)
        val actions = Proxy.newProxyInstance(DriveActions::class.java.classLoader, arrayOf(DriveActions::class.java)) { _, method, _ ->
            when (method.name) {
                "getState" -> state
                "getEnrolmentNotice" -> notice
                else -> null
            }
        } as DriveActions
        DriveHolder(actions, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), { DrivePrompts("", "", "", "") }, null, { "Pixel" }, DevicePlatform.ANDROID) { 0L }
    }

    private fun file(screen: String) = "src/test/screenshots/${screen}_${lang}_${if (dark) "dark" else "light"}.png"

    /** Draws [ui], waits for the section's heading, crops the tall window to the content and compares or records it. */
    private fun shoot(screen: String, ui: DriveUiState) {
        var heading = ""
        compose.setContent {
            heading = stringResource(Res.string.drive_connect_heading)
            DoorprintsTheme(dark = dark) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { DriveSettingsContent(ui, holder) }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(heading).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        val all = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        val end = (all.filter { it.boundsInRoot.height < image.height / 2 }.maxOf { it.boundsInRoot.bottom } + 16 * density).toInt().coerceAtMost(image.height)
        Bitmap.createBitmap(image, 0, 0, image.width, end).captureRoboImage(file(screen))
    }

    private val sampleKey = "K7MQ-4XRD-9HTW-2CFN-6PBV-3GJY-8ZA"
    private val backups = listOf(
        BackupSummary("b2", 1_760_000_100_000, 14, 3_145_728, "b2"),
        BackupSummary("b1", 1_759_900_000_000, 12, 2_621_440, "b1"),
    )

    @Test fun driveDisconnected() = shoot("drive_disconnected", DriveUiState())

    @Test fun driveRecoveryKey() = shoot(
        "drive_key",
        DriveUiState(connect = ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY, connectKey = ShownKey(sampleKey), keySaved = false),
    )

    @Test fun driveJoinWithQr() = shoot(
        "drive_join_qr",
        DriveUiState(
            connect = ConnectState.NEEDS_ENROLMENT,
            enrol = EnrolUi.Newcomer(NewcomerOffer("dp1.QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5YWJjZGVmZ2hpamtsbW5vcA", "48271936", byteArrayOf(1))),
        ),
    )

    @Test fun driveConnected() = shoot(
        "drive_ready",
        DriveUiState(
            connect = ConnectState.READY,
            backups = BackupsUi(listState = ListState.LIST, list = backups, lastBackupAt = backups[0].createdAt, lastBackupHouses = 14, auto = true),
            sync = SyncUi(info = SyncInfo(SyncState.SYNCED, 1_760_000_200_000), wifiOnly = true, pendingBytes = 12_582_912, photoStatus = PhotoNetworkStatus.WAITING_FOR_WIFI),
            devices = DevicesUi(
                devices = listOf(ListedDevice("aa", "Pixel 9", "android", true), ListedDevice("bb", "Laptop", "web", false)),
                account = "person@example.org",
            ),
        ),
    )

    @Test fun driveDeleteEverythingConfirm() = shoot(
        "drive_delete_confirm",
        DriveUiState(
            connect = ConnectState.READY,
            delete = DeleteUi(
                phase = DeletePhase.CONFIRM,
                info = DeleteConfirmInfo(DeletionLevel.L3, DeleteFactor.DEVICE_AUTH, true, 5_000),
                // In the future, so the countdown shows its full 5 seconds whenever the shot is taken.
                confirmShownAtMs = Long.MAX_VALUE / 2,
            ),
        ),
    )

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark={1}")
        fun params(): List<Array<Any>> = listOf(arrayOf("en", false), arrayOf("en", true), arrayOf("hi", false))
    }
}
