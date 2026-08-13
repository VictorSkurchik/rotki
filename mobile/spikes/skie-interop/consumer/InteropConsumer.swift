import Foundation
import P03Interop

@main
struct InteropConsumer {
    static func main() async throws {
        let probe = CompanionProbe()
        precondition(classify(probe.classify(quality: .complete)) == 1)
        precondition(classify(probe.classify(quality: .degraded)) == 1)

        let stateTask = Task {
            for await state in probe.state {
                if isOnline(state) {
                    return
                }
            }
        }
        probe.emit(state: CompanionStateOnline.shared)
        await stateTask.value

        let cancellationTask = Task {
            try await probe.awaitSwiftCancellation()
        }
        for await started in probe.cancellationStarted {
            if started.boolValue {
                break
            }
        }
        cancellationTask.cancel()
        assertCancelled(await cancellationTask.result)

        for await observed in probe.cancellationObserved {
            if observed.boolValue {
                break
            }
        }

        let kotlinCancellationTask = Task {
            try await probe.awaitKotlinCancellation()
        }
        for await started in probe.kotlinCancellationStarted {
            if started.boolValue {
                break
            }
        }
        probe.cancelFromKotlin()
        assertCancelled(await kotlinCancellationTask.result)

        for await observed in probe.kotlinCancellationObserved {
            if observed.boolValue {
                break
            }
        }

        let flowCancellationTask = Task {
            for await _ in probe.state {
                await Task.yield()
            }
        }
        await Task.yield()
        flowCancellationTask.cancel()
        _ = await flowCancellationTask.result
    }

    private static func classify(_ quality: ConnectionQuality) -> Int {
        switch quality {
        case .complete: return 1
        case .degraded: return 1
        }
    }

    private static func classify(_ state: any CompanionState) -> Int {
        switch onEnum(of: state) {
        case .unpaired: return 1
        case .deviceLocked: return 1
        case .connecting: return 1
        case .online: return 1
        case .refreshing: return 1
        case .degraded: return 1
        case .unreachable: return 1
        case .engineLocked: return 1
        case .profileMismatch: return 1
        case .incompatible: return 1
        case .revoked: return 1
        }
    }

    private static func isOnline(_ state: any CompanionState) -> Bool {
        switch onEnum(of: state) {
        case .online: return true
        default: return false
        }
    }

    private static func assertCancelled(_ result: Result<Void, any Error>) {
        switch result {
        case .success:
            preconditionFailure("The suspending Kotlin call completed without cancellation")
        case .failure(let error):
            precondition(error is CancellationError)
        }
    }
}
