import Foundation

public enum SnapshotStorageError: Error, Equatable {
    case fileTooLarge(actual: Int, maximum: Int)
}

public protocol SnapshotStorage: AnyObject {
    var exists: Bool { get }
    func read() throws -> Data
    func writeAtomically(_ data: Data) throws
    func delete() throws
}

public final class AtomicSnapshotFileStorage: SnapshotStorage {
    public static let maximumStoredBytes = (16 * 1024 * 1024) + (16 * 1024) + 15
    public let fileURL: URL
    private let fileManager: FileManager

    public init(fileURL: URL, fileManager: FileManager = .default) {
        self.fileURL = fileURL
        self.fileManager = fileManager
    }

    public var exists: Bool {
        fileManager.fileExists(atPath: fileURL.path)
    }

    public func read() throws -> Data {
        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }
        let data = try handle.read(upToCount: Self.maximumStoredBytes + 1) ?? Data()
        guard data.count <= Self.maximumStoredBytes else {
            throw SnapshotStorageError.fileTooLarge(
                actual: data.count,
                maximum: Self.maximumStoredBytes
            )
        }
        return data
    }

    public func writeAtomically(_ data: Data) throws {
        let directoryURL = fileURL.deletingLastPathComponent()
        try fileManager.createDirectory(
            at: directoryURL,
            withIntermediateDirectories: true
        )

        var directoryValues = URLResourceValues()
        directoryValues.isExcludedFromBackup = true
        var mutableDirectoryURL = directoryURL
        try mutableDirectoryURL.setResourceValues(directoryValues)

        #if os(iOS) || os(tvOS) || os(watchOS)
        try data.write(to: fileURL, options: [.atomic, .completeFileProtection])
        #else
        try data.write(to: fileURL, options: .atomic)
        #endif

        var fileValues = URLResourceValues()
        fileValues.isExcludedFromBackup = true
        var mutableFileURL = fileURL
        try mutableFileURL.setResourceValues(fileValues)
    }

    public func delete() throws {
        guard exists else { return }
        try fileManager.removeItem(at: fileURL)
    }
}
