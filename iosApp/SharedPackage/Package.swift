// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "SharedPackage",
    products: [
        .library(name: "SharedPackage", targets: ["shared"])
    ],
    targets: [
        .binaryTarget(
            name: "shared",
            // Path is relative to this package dir (iosApp/SharedPackage/): up to iosApp/, up to repo
            // root, then the Gradle XCFramework output. `../shared/...` (one level) would wrongly resolve
            // to iosApp/shared/... — it must be two levels.
            path: "../../shared/build/XCFrameworks/release/shared.xcframework"
        )
    ]
)
