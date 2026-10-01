/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

// The iOS app shell (CMP-8b): all of Doorprints' UI is Compose Multiplatform code in the Gradle module :ui, built as
// the static framework DoorprintsKit (ios/project.yml). This file only hosts it; there is no Swift UI of our own. The
// one other Swift file is the map view (MapLibreMapView.swift, CMP-8c), which Kotlin cannot build itself.
import SwiftUI
import DoorprintsKit

@main
struct DoorprintsApp: App {
    init() {
        // The map view (CMP-8c): Kotlin's common map asks this factory for MapLibre map views
        // (MapLibreMapView.swift). Registered before the first view controller, which may start on the Map.
        IosMap.shared.factory = MapLibreMapViewFactory()
        // Offline maps (S4b-FR-6): MapLibre's offline packs behind IosOfflineMaps (MapLibreOfflineMaps.swift).
        IosMap.shared.offline = MapLibreOfflineMaps()
        // Hunt mode's alerts (S4b-BL-69): the notification centre's delegate, set before the app finishes launching so
        // a tapped alert that starts the app opens its house.
        MainViewControllerKt.installNotifications()
        // The area wake-up (S4b-BL-96): the region monitor's delegate, in place before the app finishes launching, so
        // an arrival in an area that relaunches the app in the background reaches it.
        MainViewControllerKt.installAreaWakeup()
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
                // Edge to edge, as on Android: Compose draws behind the status bar and the home indicator and pads
                // its content with the window insets itself. With no argument this covers every safe-area region,
                // the keyboard's included, so SwiftUI does not shrink the view while Compose moves its content above
                // the keyboard (the IME insets).
                .ignoresSafeArea()
                // A connect link from the server's owner page (its QR code, scanned with the camera): the common
                // code checks it and asks before connecting (docs/03 §12.1).
                .onOpenURL { url in _ = MainViewControllerKt.handleOpenUrl(url: url.absoluteString) }
        }
    }
}

/// The Kotlin UIViewController (MainViewController.kt in android/ui's iosMain) inside SwiftUI.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    // Compose owns its state; SwiftUI has nothing to pass in.
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
