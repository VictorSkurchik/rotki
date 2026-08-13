# Install personal builds directly from development tools

Android will be installed as a locally signed release APK through `adb`, and iOS will be deployed as a development-signed build from Xcode to a registered device. CI may create unsigned or test artifacts but stores no signing credentials and publishes no releases; Play distribution, TestFlight, application-store metadata, auto-update, and a private download service are outside scope.
