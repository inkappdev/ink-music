import SwiftUI
import InkMusic

// ink music on the iPhone: the Kotlin screens (InkMusic.framework, built from ios/) in a window of their own, edge to
// edge (they keep clear of the notch and the home bar themselves), always dark
@main
struct InkMusicApp: App {
    var body: some Scene {
        WindowGroup {
            Screens().ignoresSafeArea().preferredColorScheme(.dark).statusBarHidden(false)
        }
    }
}

struct Screens: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { MainKt.MainViewController() }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
