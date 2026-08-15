import Foundation
import RotkiShared
import Security

final class IOSPairingRecordStore: NSObject, PairingRecordStore {
    private let queue = DispatchQueue(label: "com.rotki.companion.pairing-record")
    private let blobStore = IOSKeychainBlobStore(
        service: "com.rotki.companion.security",
        account: "pairing-record-v1",
        accessibility: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
    )

    var hasUsableRecord: Bool {
        do {
            guard let data = try blobStore.load() else { return false }
            return Self.decode(data) != nil
        } catch {
            return false
        }
    }

    func read(
        completionHandler: @escaping ((any PairingRecordReadOutcome)?, Error?) -> Void
    ) {
        queue.async { [blobStore] in
            do {
                guard let data = try blobStore.load() else {
                    completionHandler(PairingRecordReadOutcomeMissing.shared, nil)
                    return
                }
                guard let record = Self.decode(data) else {
                    completionHandler(PairingRecordReadOutcomeCorrupt.shared, nil)
                    return
                }
                completionHandler(PairingRecordReadOutcomePresent(record: record), nil)
            } catch {
                completionHandler(PairingRecordReadOutcomeUnavailable.shared, nil)
            }
        }
    }

    func write(
        record: PairingRecord,
        completionHandler: @escaping ((any PairingRecordWriteOutcome)?, Error?) -> Void
    ) {
        queue.async { [blobStore] in
            do {
                try blobStore.save(Self.encode(record))
                completionHandler(PairingRecordWriteOutcomeStored.shared, nil)
            } catch {
                completionHandler(PairingRecordWriteOutcomeUnavailable.shared, nil)
            }
        }
    }

    func delete(
        completionHandler: @escaping ((any PairingRecordDeleteOutcome)?, Error?) -> Void
    ) {
        queue.async { [blobStore] in
            do {
                try blobStore.delete()
                completionHandler(PairingRecordDeleteOutcomeDeleted.shared, nil)
            } catch {
                completionHandler(PairingRecordDeleteOutcomeUnavailable.shared, nil)
            }
        }
    }

    override var description: String {
        "IOSPairingRecordStore(redacted)"
    }

    private static func encode(_ record: PairingRecord) throws -> Data {
        try JSONSerialization.data(
            withJSONObject: [
                "engine_origin": record.engineOrigin.canonical,
                "device_session_id": record.deviceSessionId.encoded,
            ],
            options: [.sortedKeys]
        )
    }

    private static func decode(_ data: Data) -> PairingRecord? {
        guard
            let object = try? JSONSerialization.jsonObject(with: data),
            let dictionary = object as? [String: Any],
            dictionary.count == 2,
            let originText = dictionary["engine_origin"] as? String,
            let sessionText = dictionary["device_session_id"] as? String,
            let origin = (
                EngineOrigin.companion.parse(candidate: originText) as? EngineOriginParseOutcomeAccepted
            )?.origin,
            let sessionId = (
                DeviceSessionId.companion.parse(candidate: sessionText)
                    as? ProtocolValueParseOutcomeAccepted<DeviceSessionId>
            )?.value
        else {
            return nil
        }
        return PairingRecord(engineOrigin: origin, deviceSessionId: sessionId)
    }
}

final class IOSPairingCleanupJournal: NSObject, PairingCleanupJournal {
    private let queue = DispatchQueue(label: "com.rotki.companion.pairing-cleanup")
    private let blobStore = IOSKeychainBlobStore(
        service: "com.rotki.companion.security",
        account: "pairing-cleanup-v1",
        accessibility: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
    )

    var isClear: Bool {
        do {
            return try blobStore.load() == nil
        } catch {
            return false
        }
    }

    func read(
        completionHandler: @escaping ((any PairingCleanupJournalReadOutcome)?, Error?) -> Void
    ) {
        queue.async { [blobStore] in
            do {
                let marker = try blobStore.load()
                completionHandler(
                    marker == nil ?
                        PairingCleanupJournalReadOutcomeClear.shared :
                        PairingCleanupJournalReadOutcomeCleanupRequired.shared,
                    nil
                )
            } catch {
                completionHandler(PairingCleanupJournalReadOutcomeUnavailable.shared, nil)
            }
        }
    }

    func markCleanupRequired(
        completionHandler: @escaping ((any PairingCleanupJournalWriteOutcome)?, Error?) -> Void
    ) {
        queue.async { [blobStore] in
            do {
                try blobStore.save(Data([1]))
                completionHandler(PairingCleanupJournalWriteOutcomeStored.shared, nil)
            } catch {
                completionHandler(PairingCleanupJournalWriteOutcomeUnavailable.shared, nil)
            }
        }
    }

    func clear(
        completionHandler: @escaping ((any PairingCleanupJournalClearOutcome)?, Error?) -> Void
    ) {
        queue.async { [blobStore] in
            do {
                try blobStore.delete()
                completionHandler(PairingCleanupJournalClearOutcomeCleared.shared, nil)
            } catch {
                completionHandler(PairingCleanupJournalClearOutcomeUnavailable.shared, nil)
            }
        }
    }

    override var description: String {
        "IOSPairingCleanupJournal(redacted)"
    }
}

final class IOSIdempotencyKeyGenerator: NSObject, IdempotencyKeyGenerator {
    func generate() -> IdempotencyKey {
        var bytes = Data(count: 16)
        let status = bytes.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, buffer.count, buffer.baseAddress!)
        }
        precondition(status == errSecSuccess, "Secure random generation unavailable")
        let parsed = IdempotencyKey.companion.fromBytes(bytes: bytes.kotlinByteArray())
        guard let key = (
            parsed as? ProtocolValueParseOutcomeAccepted<IdempotencyKey>
        )?.value else {
            preconditionFailure("Generated idempotency key failed protocol validation")
        }
        bytes.resetBytes(in: bytes.startIndex..<bytes.endIndex)
        return key
    }

    override var description: String {
        "IOSIdempotencyKeyGenerator(redacted)"
    }
}

final class IOSEpochClock: NSObject, Clock {
    func nowEpochSeconds() -> Int64 {
        Int64(Date().timeIntervalSince1970)
    }
}

final class IOSKeychainBlobStore {
    private let service: String
    private let account: String
    private let accessibility: CFString

    init(service: String, account: String, accessibility: CFString) {
        self.service = service
        self.account = account
        self.accessibility = accessibility
    }

    func load() throws -> Data? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess, let data = result as? Data else {
            throw IOSKeychainError.operationFailed
        }
        return data
    }

    func save(_ data: Data) throws {
        var attributes = baseQuery
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = accessibility
        let addStatus = SecItemAdd(attributes as CFDictionary, nil)
        if addStatus == errSecSuccess {
            return
        }
        guard addStatus == errSecDuplicateItem else {
            throw IOSKeychainError.operationFailed
        }
        let updateStatus = SecItemUpdate(
            baseQuery as CFDictionary,
            [kSecValueData as String: data] as CFDictionary
        )
        guard updateStatus == errSecSuccess else {
            throw IOSKeychainError.operationFailed
        }
    }

    func delete() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw IOSKeychainError.operationFailed
        }
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: false,
            kSecUseDataProtectionKeychain as String: true,
        ]
    }
}

private enum IOSKeychainError: Error {
    case operationFailed
}
