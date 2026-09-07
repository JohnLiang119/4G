## 1. Platform and Test Foundation

- [x] 1.1 Raise `app` minimum SDK to API 29 and verify `gradlew.bat assembleDebug` succeeds with the Android 10 baseline.
- [x] 1.2 Add JVM and Android instrumentation test dependencies/source sets and verify `gradlew.bat testDebugUnitTest` plus `gradlew.bat connectedDebugAndroidTest` can discover a smoke test in their respective environments.

## 2. Statistics Model and Tracker

- [x] 2.1 Add immutable `FlowKey`, `AppIdentity`, `FlowRecord`, `AppStatsRow`, and `StatsSnapshot` models plus an injectable monotonic clock, and verify unit tests cover equality, protocol separation, full five-tuples, and VPN generation separation.
- [x] 2.2 Implement generation-aware, idempotent flow `open`, `resolve`, `touch`, `close`, and `reset` operations in a thread-safe tracker, and verify unit tests cover duplicate opens, duplicate closes, stale-generation events, and non-negative counts.
- [x] 2.3 Derive per-APP and global TCP/UDP totals from tracker records into immutable snapshots, and verify tests enforce `row total = TCP + UDP` and `global total = sum(rows)` for resolved, shared-UID, and Unknown groups.
- [x] 2.4 Publish the latest snapshot through a process-local atomic repository and verify concurrent reader/writer tests never expose a partially updated snapshot.

## 3. APP Identity Resolution

- [x] 3.1 Build an immutable applied-APP index containing only successfully validated package names, UIDs, and localized labels, and verify tests cover duplicate labels, missing packages, single-package UIDs, and shared UIDs.
- [x] 3.2 Implement a `ConnectivityManager` owner resolver that accepts the copied original TCP/UDP tuple and maps valid UIDs through the applied-APP index, and verify wrapper tests cover TCP, UDP, `INVALID_UID`, `SecurityException`, and package lookup failure.
- [x] 3.3 Run owner resolution once per new flow on a small bounded executor without blocking forwarding, and verify a stress test demonstrates duplicate flow packets schedule one lookup while queue rejection leaves the flow in Unknown and forwarding continues.
- [x] 3.4 Reject stale asynchronous resolver results by VPN generation and active-flow membership, and verify a delayed-result unit test cannot relabel a closed flow or a newer session.

## 4. VPN Session and Applied Selection

- [x] 4.1 Validate every selected package before adding it to the VPN builder and abort before `establish()` when no package remains, and verify tests cover an empty selection and an app uninstalled immediately before start without falling back to an all-APP VPN.
- [x] 4.2 Create a new statistics generation only after VPN establishment succeeds and clear/cancel tracker, resolver, publisher, and snapshot state during every stop/failure path, and verify service lifecycle tests start each successful session at zero.
- [x] 4.3 Keep running-session routing and labels bound to the immutable applied selection while checkbox edits remain draft, and verify an A+B to A+C scenario continues to show A+B until restart and begins A+C at zero after successful rebuild.

## 5. TCP Flow Integration

- [x] 5.1 Construct the full TCP `FlowKey` from the original outbound SYN before source/destination swapping, open one tracker record, submit ownership resolution, and store the key on the TCB; verify SYN retransmission and data tests keep the count at one and never attribute the upstream VPN socket.
- [x] 5.2 Route connect failure, RST, completed FIN, channel error, cache eviction, and engine shutdown through one idempotent TCP cleanup path, and verify each terminal-path test removes the tracker record exactly once.
- [x] 5.3 Correct the remote-EOF handling so it completes or closes the TCB instead of leaving a zero-interest entry, and verify an EOF regression test returns the TCP and per-APP counts to zero.
- [x] 5.4 Preserve a half-closed TCP flow after a single FIN until terminal closure, and verify a state-machine test shows the count remains one after one-sided FIN and becomes zero only at the terminal event.

## 6. UDP Flow Integration

- [x] 6.1 Replace the UDP channel-only cache value with an association carrying the channel, reference packet, full original `FlowKey`, identity, generation, and last-activity timestamp, and verify different destinations create distinct records while repeated datagrams on one tuple remain one.
- [x] 6.2 Refresh UDP activity for both outbound and inbound datagrams without repeating owner lookup, and verify fake-clock tests show activity at 59 seconds extends the flow lifetime.
- [x] 6.3 Add the monotonic 60-second UDP expiry sweep and idempotent error/eviction/shutdown cleanup, and verify boundary tests retain a flow before 60 seconds, remove it at 60 seconds, and allow later traffic to create a newly resolved association.
- [x] 6.4 Synchronize UDP association access between packet workers, cleanup, and snapshot publication, and verify a concurrent create/touch/expire stress test completes without collection errors, stale identity reuse, or inconsistent totals.

## 7. Shared Statistics Presentation

- [x] 7.1 Add the main-screen “作用中連線” summary and per-APP rows using the shared snapshot, including TCP, UDP, total, Unknown/shared-UID labels, top-five ordering, expansion, and zero state; verify UI tests cover multiple APPs, long/duplicate labels, overflow, and no active flows.
- [x] 7.2 Replace notification cache counts with the shared snapshot, show total plus the top two groups and remainder count, throttle ordinary updates to five seconds, and verify notification tests cover zero/one/many APPs plus silent repeated updates.
- [x] 7.3 Configure a private notification with a generic public lock-screen version, and verify a locked-device instrumentation/manual test exposes neither APP names nor connection counts.
- [x] 7.4 Update the floating window to show a collapsed APP/flow summary and an expandable top-three breakdown while preserving drag behavior, and verify interaction tests distinguish tap from drag and auto-collapse expanded details.
- [x] 7.5 Hide APP labels from the floating window while the device is locked and restore only collapsed content after unlock, and verify lock/unlock tests never reveal the previously expanded names.
- [x] 7.6 Remove direct `TCB` and UDP cache-size reads from `MainActivity` and `CellularVpnService`, and verify main screen, notification, and floating window render identical totals from the same snapshot generation.

## 8. Verification and Device Acceptance

- [x] 8.1 Run the `opsx-apply` tests (unit and integration tests matching the component) via `./gradlew test` and confirm all clock, snapshot, and caching boundary assertions pass.
- [x] 8.2 Start the application and confirm the main screen accurately totals real TCP and UDP traffic (e.g. browsing the web).
- [x] 8.3 Ensure multiple selected APPs disambiguate overlapping labels correctly on the main screen and notification.
- [x] 8.4 Verify lock screen behavior securely masks details on the floating window and notification.
- [x] 8.5 Turn off the VPN and confirm all flow state zeroes out and ceases background snapshot updates.
