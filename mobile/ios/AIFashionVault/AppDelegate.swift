import UIKit
import React
import React_RCTAppDelegate
import ReactAppDependencyProvider
import UserNotifications

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
  var window: UIWindow?

  var reactNativeDelegate: ReactNativeDelegate?
  var reactNativeFactory: RCTReactNativeFactory?

  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    let delegate = ReactNativeDelegate()
    let factory = RCTReactNativeFactory(delegate: delegate)
    delegate.dependencyProvider = RCTAppDependencyProvider()

    reactNativeDelegate = delegate
    reactNativeFactory = factory

    // LOOK_READY notification taps (cold start): fold the Look destination
    // into launchOptions so Linking.getInitialURL() delivers it alongside the
    // warm-start delegate path below. Registering the delegate before
    // startReactNative guarantees the warm-start callback is never missed.
    UNUserNotificationCenter.current().delegate = delegate

    let foldedOptions = LookNotificationRouter.launchOptions(folding: launchOptions)

    // Share-extension handoff discovery (F2): extensions cannot open the
    // host app, so the host publishes aifashionvault://share-import/<id>
    // for live App Group records at launch and on every foreground.
    // No-op when the native share-import backstop flag is off.
    ShareHandoffDiscovery.start()

    window = UIWindow(frame: UIScreen.main.bounds)

    factory.startReactNative(
      withModuleName: "AIFashionVault",
      in: window,
      launchOptions: foldedOptions
    )

    return true
  }
}

class ReactNativeDelegate: RCTDefaultReactNativeFactoryDelegate, UNUserNotificationCenterDelegate {
  override func sourceURL(for bridge: RCTBridge) -> URL? {
    self.bundleURL()
  }

  override func bundleURL() -> URL? {
#if DEBUG
    RCTBundleURLProvider.sharedSettings.jsBundleURL(forBundleRoot: "index")
#else
    Bundle.main.url(forResource: "main", withExtension: "jsbundle")
#endif
  }

  // MARK: LOOK_READY notification taps

  // Setting the notification-center delegate without implementing
  // willPresent silences foreground presentation entirely, so keep the
  // system default (banner + sound + badge as the payload allows).
  // Authorization requests remain a backend/push-infra concern.
  func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    willPresent notification: UNNotification,
    withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
  ) {
    completionHandler([.banner, .sound, .badge])
  }

  // Warm and cold-launch taps (the delegate is registered in
  // didFinishLaunching, before startReactNative, so a cold-launch tap
  // response is delivered here in the same session). JS lookId dedupe
  // makes this safe alongside the best-effort launchOptions fold.
  func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    didReceive response: UNNotificationResponse,
    withCompletionHandler completionHandler: @escaping () -> Void
  ) {
    LookNotificationRouter.route(userInfo: response.notification.request.content.userInfo)
    completionHandler()
  }
}
