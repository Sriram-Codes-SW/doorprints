// The iOS app shell (CMP-8b): all of Doorprints' UI is Compose Multiplatform code in the Gradle module :ui, built as
// the static framework DoorprintsKit (ios/project.yml). This file only hosts it; there is no Swift UI of our own.
import SwiftUI
import DoorprintsKit

@main
struct DoorprintsApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                // Edge to edge, as on Android: Compose draws behind the status bar and the home indicator and pads
                // its content with the window insets itself.
                .ignoresSafeArea()
                // The keyboard too: Compose moves its content above the keyboard (the IME insets), so SwiftUI must not
                // shrink the view as well (Compose Multiplatform's iOS integration guidance).
                .ignoresSafeArea(.keyboard)
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
