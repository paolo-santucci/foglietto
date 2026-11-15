// ShareExtensionContractTests.swift
// iosAppTests
//
// The executable half of the Share Extension's structural contract (FR-12,
// FR-13, FR-14, FR-25, FR-26, FR-27, NFR-06): the fourth target exists, the host
// embeds it, both targets carry `CODE_SIGN_ENTITLEMENTS` and their own profile
// specifier, both entitlements declare the one App Group, `Info.plist` declares
// the share service and the text activation rule, and the extension compiles
// the shared inbox WITHOUT the KMP framework.
//
// P1-P6 are the target, embed, signing, entitlement and Info.plist predicates.
// P7 is the test-registration guard (an unregistered test file is not a compile
// error — it silently never runs, OQ-24) and P8 the negative fixtures that run
// each scan over a mutated in-memory copy so a reworded project file cannot pass.
// P9 pins the launch hand-off: the host declares the scheme the extension opens
// and the extension dispatches the current (post-iOS-18) selector, write first.
// The catalog inventory (P9 in the older numbering) lives in
// LocalizationInventoryTests.swift.
//
// Everything is read from the repository through this file's compile-time
// `#filePath` — no bundle resource, no device, no extension process, no App
// Group. XCTest cannot run on Linux, so the suite is CI-only (OQ-21): it proves
// the project graph parses the way the spec describes, NOT that `xcodebuild`
// can build it. `build_ios`/`test_ios` remain the only real proof.
//
// Spec refs: FR-12, FR-13, FR-14, FR-25, FR-26, FR-27, NFR-06; §5.1.6, §5.2
// (lines 586-608), §7.1 (lines 850-854), D1/D3, assessment C-4/C-5.
// CI-verified on macos-26 iOS Simulator — not locally compilable (no macOS host).

import XCTest
import Foundation

// ---------------------------------------------------------------------------
// MARK: - Repository access
// ---------------------------------------------------------------------------

/// Repository files read from the checkout, located from `#filePath`.
///
/// A missing checkout FAILS the suite rather than skipping it: a silent skip
/// would turn every predicate below into a false green. Not `private`: the
/// catalog inventory in `LocalizationInventoryTests.swift` reuses this locator so
/// both files fail — never skip — on a missing checkout.
struct Repository {

    /// The repository root — the directory that holds `iosApp/`.
    let root: URL

    /// Resolves the checkout from this test file's own compile-time path, with a
    /// fallback for a runner whose working directory is already the repo root.
    static func locate() throws -> Repository {
        let testFileDirectory = URL(filePath: #filePath).deletingLastPathComponent()
        let iosAppDirectory = testFileDirectory.deletingLastPathComponent()
        let marker = "iosApp/iosApp.xcodeproj/project.pbxproj"

        let candidates = [
            iosAppDirectory.deletingLastPathComponent(),
            URL(filePath: FileManager.default.currentDirectoryPath)
        ]

        guard let root = candidates.first(where: {
            FileManager.default.fileExists(atPath: $0.appending(path: marker).path)
        }) else {
            throw ContractError.repositoryNotFound(paths: candidates.map(\.path))
        }
        return Repository(root: root)
    }

    /// The absolute URL of a repository-relative path that must exist.
    func url(_ relativePath: String) throws -> URL {
        let url = root.appending(path: relativePath)
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw ContractError.fileMissing(path: relativePath)
        }
        return url
    }

    /// The UTF-8 text of a repository-relative path.
    func text(_ relativePath: String) throws -> String {
        let data = try Data(contentsOf: try url(relativePath))
        return String(decoding: data, as: UTF8.self)
    }

    /// The parsed property list of a repository-relative path.
    func propertyList(_ relativePath: String) throws -> [String: Any] {
        let data = try Data(contentsOf: try url(relativePath))
        let plist = try PropertyListSerialization.propertyList(from: data, options: [], format: nil)
        guard let dictionary = plist as? [String: Any] else {
            throw ContractError.notADictionary(path: relativePath)
        }
        return dictionary
    }

    /// The parsed property list of text already in memory — the P8 fixtures read
    /// a mutated copy and never write it to disk.
    static func propertyList(from text: String) throws -> [String: Any] {
        let plist = try PropertyListSerialization.propertyList(
            from: Data(text.utf8),
            options: [],
            format: nil
        )
        guard let dictionary = plist as? [String: Any] else {
            throw ContractError.notADictionary(path: "<in-memory copy>")
        }
        return dictionary
    }
}

// ---------------------------------------------------------------------------
// MARK: - project.pbxproj object graph
// ---------------------------------------------------------------------------

/// The slice of the hand-maintained `project.pbxproj` these predicates need: one
/// section at a time, split into its top-level objects, with the scalar and
/// array accessors each object exposes in the old-style plist format.
///
/// No Xcode parser and no `plutil`: the file is UTF-8 text where every
/// top-level object definition starts a `\t\t<ID> ` line, which is exactly what
/// this reads.
private struct ProjectGraph {

    let text: String

    /// One top-level object definition and every line up to the next one.
    struct Object {
        let identifier: String
        let body: String

        /// `key = value;` anywhere in the body, without the trailing semicolon.
        ///
        /// Xcode writes `PBXBuildFile` and `PBXFileReference` objects on a SINGLE
        /// line, so matching only at the start of a line sees the object id and
        /// never `fileRef`/`path` — every such lookup would return nil.
        func scalar(_ key: String) -> String? {
            func isKeyBoundary(_ character: Character) -> Bool {
                character == "{" || character == ";" || character.isWhitespace
            }

            var searchStart = body.startIndex
            while let keyRange = body.range(of: key, range: searchStart..<body.endIndex) {
                searchStart = keyRange.upperBound
                let isDelimited = keyRange.lowerBound == body.startIndex
                    || isKeyBoundary(body[body.index(before: keyRange.lowerBound)])
                guard isDelimited, body[keyRange.upperBound...].hasPrefix(" = ") else { continue }
                let valueStart = body.index(keyRange.upperBound, offsetBy: 3)
                guard let terminator = body[valueStart...].firstIndex(of: ";") else { continue }
                return String(body[valueStart..<terminator])
                    .trimmingCharacters(in: .whitespacesAndNewlines)
            }
            return nil
        }

        /// A `key = <ID>` reference, stripped of its trailing `/* comment */`.
        func scalarIdentifier(_ key: String) -> String? {
            scalar(key)?.components(separatedBy: " ").first
        }

        /// The identifiers inside `key = ( ... );`, however the list is wrapped.
        func arrayItems(_ key: String) -> [String] {
            guard let start = body.range(of: "\(key) = (") else { return [] }
            guard let end = body.range(of: ");", range: start.upperBound..<body.endIndex) else { return [] }
            return body[start.upperBound..<end.lowerBound]
                .components(separatedBy: ",")
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .compactMap { $0.components(separatedBy: " ").first }
                .filter { !$0.isEmpty }
        }
    }

    // MARK: Sections

    /// Every top-level object of `section`, in file order.
    func objects(inSection section: String) throws -> [Object] {
        let beginMarker = "/* Begin \(section) section */"
        let endMarker = "/* End \(section) section */"
        guard let begin = text.range(of: beginMarker),
              let end = text.range(of: endMarker, range: begin.upperBound..<text.endIndex) else {
            throw ContractError.sectionMissing(section: section)
        }

        var objects: [Object] = []
        var currentIdentifier: String?
        var currentLines: [String] = []

        func flush() {
            guard let currentIdentifier else { return }
            objects.append(Object(identifier: currentIdentifier, body: currentLines.joined(separator: "\n")))
        }

        for line in text[begin.upperBound..<end.lowerBound].components(separatedBy: .newlines) {
            if let definitionIdentifier = Self.definitionIdentifier(of: line) {
                flush()
                currentIdentifier = definitionIdentifier
                currentLines = [line]
            } else if currentIdentifier != nil {
                currentLines.append(line)
            }
        }
        flush()
        return objects
    }

    /// The object id of a definition line (`\t\t<ID> ...`); nil for a blank line
    /// or for anything nested one level deeper.
    private static func definitionIdentifier(of line: String) -> String? {
        guard line.hasPrefix("\t\t") else { return nil }
        let remainder = line.dropFirst(2)
        guard !remainder.hasPrefix("\t") else { return nil }
        let identifier = remainder.prefix(8)
        guard identifier.count == 8, identifier.allSatisfy({ $0.isHexDigit }) else { return nil }
        guard remainder.dropFirst(8).hasPrefix(" ") else { return nil }
        return String(identifier)
    }

    // MARK: Targets, configurations and phases

    func target(named name: String) throws -> Object {
        let targets = try objects(inSection: "PBXNativeTarget")
        guard let target = targets.first(where: { $0.scalar("name") == name }) else {
            throw ContractError.targetMissing(name: name)
        }
        return target
    }

    /// The `PBXFileReference` a target produces (`productReference`).
    func productFileReference(of target: Object) throws -> Object {
        guard let identifier = target.scalarIdentifier("productReference") else {
            throw ContractError.referenceMissing(key: "productReference", identifier: target.identifier)
        }
        let references = try objects(inSection: "PBXFileReference")
        guard let reference = references.first(where: { $0.identifier == identifier }) else {
            throw ContractError.objectMissing(identifier: identifier)
        }
        return reference
    }

    /// The Debug and Release `XCBuildConfiguration` objects of one target,
    /// resolved through its `buildConfigurationList` (never assumed by order).
    func configurations(of target: Object) throws -> [Object] {
        guard let listIdentifier = target.scalarIdentifier("buildConfigurationList") else {
            throw ContractError.referenceMissing(key: "buildConfigurationList", identifier: target.identifier)
        }
        let lists = try objects(inSection: "XCConfigurationList")
        guard let list = lists.first(where: { $0.identifier == listIdentifier }) else {
            throw ContractError.objectMissing(identifier: listIdentifier)
        }
        let configurations = try objects(inSection: "XCBuildConfiguration")
        return try list.arrayItems("buildConfigurations").map { identifier -> Object in
            guard let configuration = configurations.first(where: { $0.identifier == identifier }) else {
                throw ContractError.objectMissing(identifier: identifier)
            }
            return configuration
        }
    }

    /// The objects of `section` that a target references under `key`
    /// (`buildPhases`, `dependencies`), in section order.
    func references(of target: Object, key: String, inSection section: String) throws -> [Object] {
        let candidates = try objects(inSection: section)
        let identifiers = target.arrayItems(key)
        return candidates.filter { identifiers.contains($0.identifier) }
    }

    /// The `PBXFileReference` of every `PBXBuildFile` a phase lists.
    func fileReferences(ofPhase phase: Object) throws -> [Object] {
        let buildFiles = try objects(inSection: "PBXBuildFile")
        let fileReferences = try objects(inSection: "PBXFileReference")
        return try phase.arrayItems("files").map { identifier -> Object in
            guard let buildFile = buildFiles.first(where: { $0.identifier == identifier }),
                  let referenceIdentifier = buildFile.scalarIdentifier("fileRef") else {
                throw ContractError.objectMissing(identifier: identifier)
            }
            guard let reference = fileReferences.first(where: { $0.identifier == referenceIdentifier }) else {
                throw ContractError.objectMissing(identifier: referenceIdentifier)
            }
            return reference
        }
    }
}

// ---------------------------------------------------------------------------
// MARK: - Errors
// ---------------------------------------------------------------------------

/// Every lookup failure names the file, section or identifier it could not
/// resolve, so a reworded project file fails with a location rather than a bare
/// assertion.
private enum ContractError: Error, CustomStringConvertible {
    case repositoryNotFound(paths: [String])
    case fileMissing(path: String)
    case notADictionary(path: String)
    case sectionMissing(section: String)
    case targetMissing(name: String)
    case objectMissing(identifier: String)
    case referenceMissing(key: String, identifier: String)

    var description: String {
        switch self {
        case let .repositoryNotFound(paths):
            return "Repository root not found from #filePath. Tried: \(paths.joined(separator: ", "))"
        case let .fileMissing(path):
            return "Required file is missing: \(path)"
        case let .notADictionary(path):
            return "\(path) has no property-list dictionary at its root"
        case let .sectionMissing(section):
            return "project.pbxproj has no '/* Begin \(section) section */' block"
        case let .targetMissing(name):
            return "project.pbxproj has no PBXNativeTarget named '\(name)'"
        case let .objectMissing(identifier):
            return "project.pbxproj references object \(identifier), which is not defined"
        case let .referenceMissing(key, identifier):
            return "project.pbxproj object \(identifier) has no '\(key)' reference"
        }
    }
}

// ---------------------------------------------------------------------------
// MARK: - Scans under mutation
// ---------------------------------------------------------------------------

/// The P8 predicates, expressed over the *text* of a project or plist file, so the
/// very same scan runs green on the checkout and red on a mutated copy.
///
/// Each returns the offender it could not find (`nil` when the text satisfies the
/// scan): the fixtures assert the name, so a scan cannot pass by reporting a
/// generic failure.
private enum ProjectScan {

    /// A native target with this name exists (fixture: the target block removed).
    static func target(_ name: String, inProject text: String) -> String? {
        do {
            _ = try ProjectGraph(text: text).target(named: name)
            return nil
        } catch {
            return "project.pbxproj has no PBXNativeTarget named '\(name)'"
        }
    }

    /// The host carries a copy-files phase named `name` that embeds into
    /// `subfolderSpec` (fixture: the embed phase dropped).
    static func embedPhase(inProject text: String, named name: String, subfolderSpec: String) -> String? {
        let graph = ProjectGraph(text: text)
        guard let host = try? graph.target(named: "iosApp") else {
            return "project.pbxproj has no iosApp target to embed the extension from"
        }
        let phases = (try? graph.references(
            of: host,
            key: "buildPhases",
            inSection: "PBXCopyFilesBuildPhase"
        )) ?? []
        let embed = phases.contains {
            unquoted($0.scalar("name")) == name && $0.scalar("dstSubfolderSpec") == subfolderSpec
        }
        guard embed else {
            return "the iosApp target has no copy-files phase named '\(name)' with dstSubfolderSpec \(subfolderSpec)"
        }
        return nil
    }

    /// Every entitlements file declares exactly `group` (fixture: one group id
    /// changed).
    static func applicationGroup(_ group: String, inEntitlements files: [String: String]) -> String? {
        for path in files.keys.sorted() {
            let plist = (try? Repository.propertyList(from: files[path] ?? "")) ?? [:]
            let groups = plist["com.apple.security.application-groups"] as? [String] ?? []
            guard groups == [group] else {
                return "\(path) declares \(groups), expected exactly [\(group)]"
            }
        }
        return nil
    }

    /// The provisioning profile map of a named export-options template: one entry
    /// per signed bundle id, each with its OWN placeholder (fixture: the two
    /// placeholders collapsed to one).
    static func provisioningProfiles(
        inTemplate text: String,
        named path: String,
        identifiers: [String]
    ) -> String? {
        let profiles = placeholders(inTemplate: text)
        var seen: Set<String> = []
        for identifier in identifiers {
            guard let placeholder = profiles[identifier] else {
                return "\(path) has no provisioningProfiles entry for \(identifier)"
            }
            guard seen.insert(placeholder).inserted else {
                return "\(path) maps \(identifier) to \(placeholder), which another bundle id already uses"
            }
        }
        guard profiles.count == identifiers.count else {
            return "\(path) maps \(profiles.count) bundle id(s), expected \(identifiers.count): \(profiles.keys.sorted())"
        }
        return nil
    }

    /// The `provisioningProfiles` map itself, empty when the template has none.
    static func placeholders(inTemplate text: String) -> [String: String] {
        let plist = (try? Repository.propertyList(from: text)) ?? [:]
        return plist["provisioningProfiles"] as? [String: String] ?? [:]
    }

    private static func unquoted(_ value: String?) -> String? {
        guard let value else { return nil }
        return value.trimmingCharacters(in: CharacterSet(charactersIn: "\""))
    }
}

// ---------------------------------------------------------------------------
// MARK: - ShareExtensionContractTests
// ---------------------------------------------------------------------------

/// P1-P8 of the Share Extension contract (§7.1 lines 850-860).
final class ShareExtensionContractTests: XCTestCase {

    private static let targetName = "FogliettoShareExtension"
    private static let bundleIdentifier = "com.paolosantucci.foglietto.ShareExtension"
    private static let appGroup = "group.paolosantucci.foglietto"

    private static let testTargetName = "iosAppTests"
    private static let hostBundleIdentifier = "com.paolosantucci.foglietto"
    private static let embedPhaseName = "Embed App Extensions"
    private static let exportOptionsPath = "ExportOptions.plist.template"
    private static let hostEntitlementsPath = "iosApp/iosApp/iosApp.entitlements"
    private static let extensionEntitlementsPath = "iosApp/ShareExtension/ShareExtension.entitlements"

    /// Every test file this wave adds. All four must be compiled into
    /// `iosAppTests`: an unregistered file is not a compile error, so `test_ios`
    /// would stay green while it silently never runs (OQ-24).
    private static let newTestFiles = [
        "ShareInboxTests.swift",
        "ShareInboxDrainerTests.swift",
        "ShareExtensionContractTests.swift",
        "LocalizationInventoryTests.swift"
    ]

    // -----------------------------------------------------------------------
    // MARK: P1 - the target exists with the right product type and bundle id
    // -----------------------------------------------------------------------

    /// A fourth native target of the app-extension product type, producing the
    /// `.appex` under the extension's own bundle id (FR-25).
    func testP1ExtensionTargetExistsAsAnAppExtension() throws {
        let graph = try makeGraph()
        let target = try graph.target(named: Self.targetName)

        XCTAssertEqual(
            unquoted(target.scalar("productType")),
            "com.apple.product-type.app-extension",
            "\(Self.targetName) must be an app extension, not an application"
        )

        let product = try graph.productFileReference(of: target)
        XCTAssertEqual(product.scalar("path"), "FogliettoShareExtension.appex")
        XCTAssertEqual(unquoted(product.scalar("explicitFileType")), "wrapper.app-extension")

        let configurations = try graph.configurations(of: target)
        XCTAssertEqual(
            configurations.compactMap { $0.scalar("name") }.sorted(),
            ["Debug", "Release"],
            "An XCConfigurationList needs exactly one Debug and one Release configuration"
        )
        for configuration in configurations {
            XCTAssertEqual(
                configuration.scalar("PRODUCT_BUNDLE_IDENTIFIER"),
                Self.bundleIdentifier,
                "The extension's bundle id is fixed by OQ-09/§5.1.6"
            )
        }
    }

    // -----------------------------------------------------------------------
    // MARK: P2 - the host embeds the extension and depends on it
    // -----------------------------------------------------------------------

    /// The host's "Embed App Extensions" copy phase carries the `.appex`, and a
    /// `PBXTargetDependency`/`PBXContainerItemProxy` pair makes the build order
    /// explicit (FR-25; without the embed the archive ships with no extension).
    func testP2HostEmbedsExtensionAndDependsOnIt() throws {
        let graph = try makeGraph()
        let host = try graph.target(named: "iosApp")
        let extensionTarget = try graph.target(named: Self.targetName)

        let copyPhases = try graph.references(
            of: host,
            key: "buildPhases",
            inSection: "PBXCopyFilesBuildPhase"
        )
        XCTAssertEqual(copyPhases.count, 1, "The host declares exactly one copy-files phase")
        let embedPhase = try XCTUnwrap(copyPhases.first, "The host must embed the extension")
        XCTAssertEqual(
            embedPhase.scalar("dstSubfolderSpec"),
            "13",
            "dstSubfolderSpec 13 is 'PlugIns' — the only destination that embeds an .appex"
        )
        XCTAssertEqual(
            unquoted(embedPhase.scalar("name")),
            "Embed App Extensions",
            "The phase must be the named Embed App Extensions phase, not a generic copy"
        )

        let embeddedReferences = try graph.fileReferences(ofPhase: embedPhase)
        XCTAssertEqual(
            embeddedReferences.compactMap { $0.scalar("path") },
            ["FogliettoShareExtension.appex"],
            "The embed phase must carry the extension product and nothing else"
        )
        XCTAssertEqual(
            embeddedReferences.first?.identifier,
            extensionTarget.scalarIdentifier("productReference"),
            "The embedded build file must reference THIS target's product"
        )

        let dependencies = try graph.references(
            of: host,
            key: "dependencies",
            inSection: "PBXTargetDependency"
        )
        XCTAssertEqual(
            host.arrayItems("dependencies").count,
            1,
            "The host must declare exactly one dependency — the extension"
        )
        XCTAssertEqual(dependencies.count, 1, "The host declares exactly one target dependency")
        let dependency = try XCTUnwrap(dependencies.first, "The host must depend on the extension")
        XCTAssertEqual(
            dependency.scalarIdentifier("target"),
            extensionTarget.identifier,
            "The dependency must target \(Self.targetName)"
        )

        let proxyIdentifier = try XCTUnwrap(
            dependency.scalarIdentifier("targetProxy"),
            "The dependency must resolve through a PBXContainerItemProxy"
        )
        let proxies = try graph.objects(inSection: "PBXContainerItemProxy")
        let proxy = try XCTUnwrap(
            proxies.first { $0.identifier == proxyIdentifier },
            "The dependency's targetProxy must resolve to a PBXContainerItemProxy"
        )
        XCTAssertEqual(
            proxy.scalar("remoteGlobalIDString"),
            extensionTarget.identifier,
            "The proxy must point back at the extension target"
        )
        XCTAssertEqual(proxy.scalar("remoteInfo"), Self.targetName)
    }

    // -----------------------------------------------------------------------
    // MARK: P3 - Debug/Release on both targets, one profile specifier each
    // -----------------------------------------------------------------------

    /// Two configurations per target, `CODE_SIGN_ENTITLEMENTS` on both targets,
    /// and a per-target `PROVISIONING_PROFILE_SPECIFIER` so the host and the
    /// extension sign with different profiles (FR-28 groundwork, NFR-07).
    func testP3BothTargetsHaveConfigurationsWithPerTargetSigning() throws {
        let graph = try makeGraph()
        let host = try graph.target(named: "iosApp")
        let extensionTarget = try graph.target(named: Self.targetName)

        let extensionConfigurations = try graph.configurations(of: extensionTarget)
        XCTAssertEqual(
            extensionConfigurations.compactMap { $0.scalar("name") }.sorted(),
            ["Debug", "Release"]
        )
        for configuration in extensionConfigurations {
            let name = configuration.scalar("name") ?? "<unnamed>"
            XCTAssertEqual(
                configuration.scalar("CODE_SIGN_ENTITLEMENTS"),
                "ShareExtension/ShareExtension.entitlements",
                "The extension must sign with the App Group entitlement (\(name))"
            )
            XCTAssertEqual(
                unquoted(configuration.scalar("PROVISIONING_PROFILE_SPECIFIER")),
                "$(IOS_EXTENSION_PROFILE_SPECIFIER)",
                "The extension signs with its own profile (\(name))"
            )
            XCTAssertEqual(configuration.scalar("APPLICATION_EXTENSION_API_ONLY"), "YES")
            XCTAssertEqual(configuration.scalar("SKIP_INSTALL"), "YES")
            XCTAssertEqual(configuration.scalar("IPHONEOS_DEPLOYMENT_TARGET"), "26.0")
            XCTAssertEqual(
                configuration.scalar("SWIFT_VERSION"),
                "6.0",
                "The extension must compile in the host's Swift language mode (\(name))"
            )
        }

        let hostConfigurations = try graph.configurations(of: host)
        XCTAssertEqual(
            hostConfigurations.compactMap { $0.scalar("name") }.sorted(),
            ["Debug", "Release"]
        )
        for configuration in hostConfigurations {
            let name = configuration.scalar("name") ?? "<unnamed>"
            XCTAssertEqual(
                configuration.scalar("CODE_SIGN_ENTITLEMENTS"),
                "iosApp/iosApp.entitlements",
                "The host must sign with the App Group entitlement (\(name))"
            )
            XCTAssertEqual(
                unquoted(configuration.scalar("PROVISIONING_PROFILE_SPECIFIER")),
                "$(IOS_APP_PROFILE_SPECIFIER)",
                "The host signs with the app profile (\(name))"
            )
        }

        let objectVersionLines = graph.text
            .components(separatedBy: .newlines)
            .filter { $0.hasPrefix("\tobjectVersion = ") }
        XCTAssertEqual(
            objectVersionLines,
            ["\tobjectVersion = 56;"],
            "objectVersion stays 56 — no Xcode format churn (§5.2)"
        )
    }

    // -----------------------------------------------------------------------
    // MARK: P4 - one App Group, nothing else, on both targets
    // -----------------------------------------------------------------------

    /// Both entitlement files declare exactly `group.paolosantucci.foglietto`
    /// under `com.apple.security.application-groups` and nothing else: the App
    /// Group carries the inbox only, never recovery or settings (FR-26, FR-27).
    func testP4BothEntitlementsDeclareOnlyTheAppGroup() throws {
        let repository = try Repository.locate()

        for path in ["iosApp/iosApp/iosApp.entitlements", "iosApp/ShareExtension/ShareExtension.entitlements"] {
            let entitlements = try repository.propertyList(path)
            XCTAssertEqual(
                entitlements.count,
                1,
                "\(path) must hold the App Group entitlement and nothing else: \(entitlements.keys.sorted())"
            )
            XCTAssertEqual(
                entitlements["com.apple.security.application-groups"] as? [String],
                [Self.appGroup],
                "\(path) must declare exactly [\(Self.appGroup)] (FR-26)"
            )
        }
    }

    // -----------------------------------------------------------------------
    // MARK: P5 - the extension advertises itself as a share target for text
    // -----------------------------------------------------------------------

    /// `NSExtensionPointIdentifier`, the text activation rule, `XPC!` and the
    /// principal class are what make the extension appear in the share sheet for
    /// plain text and nothing else (FR-12, FR-15).
    func testP5InfoPlistDeclaresShareServiceAndTextActivation() throws {
        let repository = try Repository.locate()
        let info = try repository.propertyList("iosApp/ShareExtension/Info.plist")

        XCTAssertEqual(info["CFBundlePackageType"] as? String, "XPC!")
        XCTAssertEqual(info["CFBundleDisplayName"] as? String, "Foglietto")
        XCTAssertEqual(
            info["CFBundleName"] as? String,
            "Foglietto",
            "The display name is the proper noun, a literal — never a catalog key (OQ-07)"
        )

        let extensionDictionary = try XCTUnwrap(
            info["NSExtension"] as? [String: Any],
            "Info.plist must carry an NSExtension dictionary"
        )
        XCTAssertEqual(
            extensionDictionary["NSExtensionPointIdentifier"] as? String,
            "com.apple.share-services",
            "Only the share-services point makes the extension a share target"
        )

        let principalClass = try XCTUnwrap(extensionDictionary["NSExtensionPrincipalClass"] as? String)
        XCTAssertTrue(
            principalClass.hasSuffix(".ShareViewController"),
            "The principal class must end in .ShareViewController, got '\(principalClass)'"
        )
        XCTAssertEqual(
            principalClass,
            "$(PRODUCT_MODULE_NAME).ShareViewController",
            "The principal class must stay module-qualified so the extension target resolves it"
        )

        let attributes = try XCTUnwrap(
            extensionDictionary["NSExtensionAttributes"] as? [String: Any],
            "The share target must declare NSExtensionAttributes"
        )
        let rule = try XCTUnwrap(
            attributes["NSExtensionActivationRule"] as? String,
            "The share target must declare an activation rule or it accepts every item"
        )
        for token in ["SUBQUERY", "registeredTypeIdentifiers", "UTI-CONFORMS-TO", "public.plain-text", "public.text"] {
            XCTAssertTrue(
                rule.contains(token),
                "The activation rule must contain '\(token)': \(rule)"
            )
        }
    }

    // -----------------------------------------------------------------------
    // MARK: P6 - the scheme exists and the extension compiles the inbox only
    // -----------------------------------------------------------------------

    /// The shared scheme exists, the extension's sources phase holds exactly the
    /// controller and the SAME `ShareInbox.swift` the host compiles (one
    /// definition of the inbox grammar, D1/D2), and the target links no KMP
    /// framework (FR-14).
    func testP6SchemeExistsAndExtensionCompilesTheInboxWithoutTheSharedFramework() throws {
        let repository = try Repository.locate()
        let graph = try makeGraph()
        let extensionTarget = try graph.target(named: Self.targetName)

        let scheme = try repository.text(Self.schemePath)
        XCTAssertTrue(
            scheme.contains(extensionTarget.identifier),
            "\(Self.schemePath) must build the \(Self.targetName) blueprint"
        )

        let sourcePhases = try graph.references(
            of: extensionTarget,
            key: "buildPhases",
            inSection: "PBXSourcesBuildPhase"
        )
        let sourcesPhase = try XCTUnwrap(sourcePhases.first, "The extension needs a sources phase")
        let compiledPaths = try graph.fileReferences(ofPhase: sourcesPhase).compactMap { $0.scalar("path") }
        XCTAssertEqual(
            Set(compiledPaths),
            ["ShareViewController.swift", "ShareInbox.swift"],
            "The extension compiles its controller plus the shared inbox definition, nothing else"
        )

        let frameworkPhases = try graph.references(
            of: extensionTarget,
            key: "buildPhases",
            inSection: "PBXFrameworksBuildPhase"
        )
        let frameworkPhase = try XCTUnwrap(frameworkPhases.first, "The extension needs a frameworks phase")
        XCTAssertEqual(
            frameworkPhase.arrayItems("files"),
            [],
            "The extension links nothing — no shared.xcframework, no package product (FR-14, D1)"
        )
        XCTAssertNil(
            extensionTarget.scalar("packageProductDependencies"),
            "The extension must not take a Swift-package product dependency"
        )

        let resourcesPhases = try graph.references(
            of: extensionTarget,
            key: "buildPhases",
            inSection: "PBXResourcesBuildPhase"
        )
        let extensionRegion = [
            extensionTarget.body,
            sourcesPhase.body,
            frameworkPhase.body
        ] + resourcesPhases.map(\.body)
        XCTAssertFalse(
            extensionRegion.joined().contains("xcframework"),
            "No xcframework may appear in the extension's target or build phases (FR-14)"
        )
    }

    // -----------------------------------------------------------------------
    // MARK: P7 - every new test file is compiled into the test target
    // -----------------------------------------------------------------------

    /// The `iosAppTests` Sources phase compiles all four files this wave adds.
    /// An unregistered file is not a compile error and does not fail
    /// `test_ios`'s "Executed 0 tests" guard — it simply never runs (OQ-24).
    func testP7EveryNewTestFileIsRegisteredInTheSourcesPhase() throws {
        let graph = try makeGraph()
        let target = try graph.target(named: Self.testTargetName)
        let phases = try graph.references(
            of: target,
            key: "buildPhases",
            inSection: "PBXSourcesBuildPhase"
        )
        let phase = try XCTUnwrap(
            phases.first,
            "\(Self.testTargetName) needs a PBXSourcesBuildPhase"
        )
        let compiled = Set(try graph.fileReferences(ofPhase: phase).compactMap { $0.scalar("path") })

        let missing = Self.newTestFiles.filter { !compiled.contains($0) }
        XCTAssertEqual(
            missing,
            [],
            "PBXSourcesBuildPhase \(phase.identifier) of the \(Self.testTargetName) target does not compile: "
                + missing.joined(separator: ", ")
                + " — an unregistered test file never runs, and the 'Executed 0 tests' guard cannot see it (OQ-24)"
        )
    }

    // -----------------------------------------------------------------------
    // MARK: P8 - the scans fail on mutated copies, not only on the checkout
    // -----------------------------------------------------------------------

    // Each fixture mutates an in-memory STRING — the file on disk is never
    // written — and asserts the scan rejects it while naming the offender, so a
    // reworded project file cannot keep the scan green. The positive leg runs
    // first, so a scan that always failed could not pass these cases either.

    /// Drop the extension's `PBXNativeTarget` block: the target scan must name
    /// the missing target.
    func testP8aRemovingTheExtensionTargetBlockFailsNamingTheTarget() throws {
        let graph = try makeGraph()
        let target = try graph.target(named: Self.targetName)
        XCTAssertNil(
            ProjectScan.target(Self.targetName, inProject: graph.text),
            "the checkout must satisfy the scan the fixture is about to break"
        )

        let mutant = try removingObject(target.identifier, from: graph.text)
        let offender = ProjectScan.target(Self.targetName, inProject: mutant)
        XCTAssertNotNil(offender, "removing the \(Self.targetName) target block must fail the scan")
        XCTAssertTrue(
            offender?.contains(Self.targetName) == true,
            "the failure must name the target '\(Self.targetName)', got: \(offender ?? "nil")"
        )
    }

    /// Change one entitlements group id: the group scan must name the file that
    /// no longer declares the App Group.
    func testP8bChangingOneEntitlementsGroupFailsNamingTheFile() throws {
        let repository = try Repository.locate()
        let entitlements = [
            Self.hostEntitlementsPath: try repository.text(Self.hostEntitlementsPath),
            Self.extensionEntitlementsPath: try repository.text(Self.extensionEntitlementsPath)
        ]
        XCTAssertNil(
            ProjectScan.applicationGroup(Self.appGroup, inEntitlements: entitlements),
            "the checkout must satisfy the scan the fixture is about to break"
        )

        let mutatedGroup = Self.appGroup + ".shared"
        var mutant = entitlements
        mutant[Self.extensionEntitlementsPath] = try XCTUnwrap(
            entitlements[Self.extensionEntitlementsPath]
        ).replacingOccurrences(of: Self.appGroup, with: mutatedGroup)

        let offender = ProjectScan.applicationGroup(Self.appGroup, inEntitlements: mutant)
        XCTAssertNotNil(offender, "a changed App Group id must fail the scan")
        XCTAssertTrue(
            offender?.contains(Self.extensionEntitlementsPath) == true,
            "the failure must name \(Self.extensionEntitlementsPath), got: \(offender ?? "nil")"
        )
        XCTAssertTrue(
            offender?.contains(mutatedGroup) == true,
            "the failure must name the wrong group '\(mutatedGroup)', got: \(offender ?? "nil")"
        )
    }

    /// Drop the "Embed App Extensions" phase: the embed scan must name
    /// `dstSubfolderSpec 13`, the only destination that ships the `.appex`.
    func testP8cDroppingTheEmbedPhaseFailsNamingTheSubfolderSpec() throws {
        let graph = try makeGraph()
        XCTAssertNil(
            ProjectScan.embedPhase(inProject: graph.text, named: Self.embedPhaseName, subfolderSpec: "13"),
            "the checkout must satisfy the scan the fixture is about to break"
        )

        let embedPhase = try XCTUnwrap(
            try graph.objects(inSection: "PBXCopyFilesBuildPhase").first {
                unquoted($0.scalar("name")) == Self.embedPhaseName
            },
            "the checkout must carry the \(Self.embedPhaseName) copy-files phase"
        )
        let mutant = try removingObject(embedPhase.identifier, from: graph.text)

        let offender = ProjectScan.embedPhase(inProject: mutant, named: Self.embedPhaseName, subfolderSpec: "13")
        XCTAssertNotNil(offender, "dropping the embed phase must fail the scan")
        XCTAssertTrue(
            offender?.contains("dstSubfolderSpec 13") == true,
            "the failure must name dstSubfolderSpec 13, got: \(offender ?? "nil")"
        )
    }

    /// Collapse the two `ExportOptions.plist.template` placeholders into one: the
    /// profile scan must name the SECOND signed bundle id rather than letting one
    /// profile sign both (FR-28 groundwork, NFR-07).
    func testP8dCollapsingTheProvisioningPlaceholdersFailsNamingTheSecondBundleIdentifier() throws {
        let repository = try Repository.locate()
        let template = try repository.text(Self.exportOptionsPath)
        let identifiers = [Self.hostBundleIdentifier, Self.bundleIdentifier]
        XCTAssertNil(
            ProjectScan.provisioningProfiles(inTemplate: template, named: Self.exportOptionsPath, identifiers: identifiers),
            "the checkout must satisfy the scan the fixture is about to break"
        )

        let placeholders = ProjectScan.placeholders(inTemplate: template)
        let hostPlaceholder = try XCTUnwrap(
            placeholders[Self.hostBundleIdentifier],
            "\(Self.exportOptionsPath) must map \(Self.hostBundleIdentifier) to a placeholder"
        )
        let extensionPlaceholder = try XCTUnwrap(
            placeholders[Self.bundleIdentifier],
            "\(Self.exportOptionsPath) must map \(Self.bundleIdentifier) to its own placeholder"
        )
        let mutant = template.replacingOccurrences(of: extensionPlaceholder, with: hostPlaceholder)

        let offender = ProjectScan.provisioningProfiles(
            inTemplate: mutant,
            named: Self.exportOptionsPath,
            identifiers: identifiers
        )
        XCTAssertNotNil(offender, "one placeholder for both bundle ids must fail the scan")
        XCTAssertTrue(
            offender?.contains(Self.bundleIdentifier) == true,
            "the failure must name the second bundle id '\(Self.bundleIdentifier)', got: \(offender ?? "nil")"
        )
    }

    // -----------------------------------------------------------------------
    // MARK: P9 - the extension asks the host to open, and the host answers
    // -----------------------------------------------------------------------

    /// The extension's hand-off half: the host declares the scheme the extension
    /// opens, the extension dispatches the CURRENT `openURL:` selector (the
    /// legacy one is a no-op since iOS 18), and the launch is gated on a
    /// successful inbox write so a failed write never fakes a delivery (FR-29).
    ///
    /// This is a source scan: whether the dispatch actually foregrounds the app
    /// on a device is not assertable here (there is no supported API to observe
    /// it) — the inbox fallback is what keeps a failed dispatch harmless.
    func testP9ExtensionAsksTheHostToOpenAndTheHostDeclaresTheScheme() throws {
        let repository = try Repository.locate()
        let controller = try repository.text("iosApp/ShareExtension/ShareViewController.swift")
        let hostInfo = try repository.propertyList("iosApp/iosApp/Info.plist")

        // Code only: the controller's header documents the absences asserted
        // below (the legacy selector, UIApplication.shared), and a scan over the
        // raw text would flag its own documentation.
        let code = controller
            .components(separatedBy: .newlines)
            .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
            .joined(separator: "\n")

        // The scheme the extension opens, read out of its own single literal.
        let literal = try XCTUnwrap(
            controller
                .components(separatedBy: .newlines)
                .first { $0.contains("hostAppURLString = \"") },
            "ShareViewController.swift must declare the host URL it opens (FR-29)"
        )
        let hostURL = try XCTUnwrap(
            literal.split(separator: "\"").dropFirst().first.map { String($0) },
            "the hostAppURLString literal must be a quoted URL"
        )
        let scheme = try XCTUnwrap(
            hostURL.split(separator: ":").first.map { String($0) },
            "the host URL must be an absolute '<scheme>://…' URL"
        )

        let urlTypes = try XCTUnwrap(
            hostInfo["CFBundleURLTypes"] as? [[String: Any]],
            "iosApp/Info.plist must declare CFBundleURLTypes, or the extension's openURL: " +
            "dispatch has no registered scheme to land on (FR-29)"
        )
        let declaredSchemes = urlTypes
            .compactMap { $0["CFBundleURLSchemes"] as? [String] }
            .flatMap { $0 }
        XCTAssertTrue(
            declaredSchemes.contains(scheme),
            "the host must declare the '\(scheme)' scheme the extension opens, got: \(declaredSchemes)"
        )

        XCTAssertTrue(
            controller.contains("openURL:options:completionHandler:"),
            "the extension must dispatch the current openURL:options:completionHandler: selector"
        )
        XCTAssertFalse(
            code.contains("NSSelectorFromString(\"openURL:\")"),
            "the legacy one-argument openURL: selector is a silent no-op since iOS 18"
        )
        XCTAssertFalse(
            code.contains("UIApplication.shared"),
            "UIApplication.shared is unavailable in an app extension — the dispatch must go " +
            "through the responder chain"
        )

        let write = try XCTUnwrap(
            code.range(of: "ShareInbox().write(payload)"),
            "the extension must write the raw payload to the inbox"
        )
        let launch = try XCTUnwrap(
            code.range(of: "openHostApp()"),
            "the extension must attempt the host launch after the write"
        )
        XCTAssertTrue(
            write.lowerBound < launch.lowerBound,
            "the inbox write must come BEFORE the launch, so a failed dispatch still delivers " +
            "the payload on the host's next activation (FR-15/FR-29)"
        )
    }

    // -----------------------------------------------------------------------
    // MARK: P10 - the capture has a last-resort text source
    // -----------------------------------------------------------------------

    /// Not every host app publishes the shared text as an `NSItemProvider`: some
    /// only set `NSExtensionItem.attributedContentText`. Without the fallback the
    /// extension completed and wrote nothing, so the share vanished with no
    /// error anywhere — the reported "it opens and closes and nothing arrives".
    func testP10CaptureFallsBackToTheItemContentText() throws {
        let controller = try Repository.locate()
            .text("iosApp/ShareExtension/ShareViewController.swift")

        XCTAssertTrue(
            controller.contains("attributedContentText"),
            "the extension must fall back to NSExtensionItem.attributedContentText when no " +
            "attachment yields a payload"
        )
        XCTAssertTrue(
            controller.contains("loadNextCandidate()"),
            "the fallback must stay on the attach-then-fall-back path, not replace the loader"
        )
    }

    // -----------------------------------------------------------------------
    // MARK: - Helpers
    // -----------------------------------------------------------------------

    private static let schemePath = "iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/ShareExtension.xcscheme"

    private func makeGraph() throws -> ProjectGraph {
        let repository = try Repository.locate()
        return ProjectGraph(text: try repository.text("iosApp/iosApp.xcodeproj/project.pbxproj"))
    }

    /// A copy of the project text with one top-level object definition removed —
    /// its `\t\t<ID> ` line through the closing `\t\t};`. The file on disk is
    /// never written: every fixture mutates text in memory.
    private func removingObject(_ identifier: String, from text: String) throws -> String {
        var lines = text.components(separatedBy: "\n")
        guard let start = lines.firstIndex(where: { $0.hasPrefix("\t\t\(identifier) ") }),
              let end = lines[start...].firstIndex(of: "\t\t};") else {
            throw ContractError.objectMissing(identifier: identifier)
        }
        lines.removeSubrange(start...end)
        return lines.joined(separator: "\n")
    }

    private func unquoted(_ value: String?) -> String? {
        guard let value else { return nil }
        return value.trimmingCharacters(in: CharacterSet(charactersIn: "\""))
    }
}
