## Context

See `proposal.md` for motivation and `specs/app-connection-stats/spec.md` for the behavior contract.

The Android app currently targets API 24+, establishes one per-app `VpnService`, and forwards IPv4 TCP/UDP packets through a Java LocalVPN engine. `TCPOutput` keys a `TCB` cache by destination address, destination port, and source port; `UDPOutput` uses a similar key whose value is only a `DatagramChannel`. Neither path retains the originating UID or package identity.

`MainActivity` and `CellularVpnService` independently poll the two static cache sizes every second. The TCP count includes every retained TCB state and can retain some remote-EOF entries; the UDP cache has no activity timeout and therefore behaves more like a session-lifetime mapping count than an active-flow count. The three presentation surfaces do not consume an atomic shared snapshot.

All target devices are Android 10 or newer and multiple packages may be selected in one VPN session. The change therefore can rely on API 29 connection-owner lookup and does not need an older-device fallback. The existing LocalVPN forwarding engine remains in place, so this design only observes TCP/UDP flows that enter that engine; it is not a system-wide connection monitor.

## Goals / Non-Goals

**Goals:**
- Resolve each newly observed LocalVPN TCP/UDP flow to an originating UID without blocking or changing packet forwarding.
- Keep flow identity and lifecycle state in one session-scoped, thread-safe tracker.
- Derive internally consistent immutable snapshots for the main screen, notification, and floating window.
- Make flow creation, termination, UDP expiry, VPN restart, unknown ownership, and shared UID behavior deterministic and testable.
- Minimize work on packet-processing threads and avoid package-manager or owner lookups on every packet or UI refresh.

**Non-Goals:**
- Persist historical connections, cumulative session totals, byte counts, destinations, URLs, DNS names, or packet payloads.
- Enumerate connections outside this VPN or prove that a remote endpoint successfully processed application data.
- Add IPv6 forwarding, replace the LocalVPN engine, or redesign the complete application UI.
- Attribute a shared UID to one package when Android cannot provide that distinction.

## Decisions

### Decision 1: Raise the platform baseline to API 29

Set `minSdk` to 29 and use `ConnectivityManager.getConnectionOwnerUid(protocol, local, remote)` for TCP and UDP ownership lookup.

The lookup receives an immutable copy of the original outbound tuple before `Packet.swapSourceAndDestination()` and before the LocalVPN protected upstream socket is used:

```text
local  = original source address + source port
remote = original destination address + destination port
```

The proxy-side upstream tuple must never be used because its owner is the router application itself.

**Alternatives considered:**
- Keep API 24 and read `/proc/net`: rejected because it is not a reliable public Android interface and is unavailable to ordinary apps on modern Android.
- Keep API 24 with an aggregate fallback: rejected because every target device is API 29+ and the requested multi-APP grouping would behave differently by OS version.
- Infer ownership from the selected package list: rejected because more than one APP can be applied in the same session.

### Decision 2: Resolve identity once per new flow without blocking forwarding

Create a `ConnectionOwnerResolver` owned by `CellularVpnService`. Packet-processing code copies the new flow key and submits one lookup to a small bounded executor, while forwarding continues immediately with a provisional `Unknown` identity. A completed result may move the still-active record from `Unknown` to a resolved group only when its VPN generation still matches.

Owner lookup, package lookup, and label loading are forbidden from per-packet activity updates and snapshot rendering. Queue saturation, `Process.INVALID_UID`, `SecurityException`, missing packages, or stale results leave the record in `Unknown` and do not fail the flow.

At VPN start, build an immutable `AppliedAppIndex` from only the packages successfully added to the VPN builder:

```text
uid -> package names -> localized labels
```

If one UID maps to multiple packages, the resolver returns one shared-UID identity containing every visible candidate. It never chooses one package or duplicates the flow across packages.

**Alternatives considered:**
- Synchronous lookup on TCP/UDP worker threads: rejected because a slow framework lookup would stall unrelated traffic.
- Re-query on every packet or every second: rejected due to cost, inconsistent results, and increased race exposure.
- Query arbitrary installed packages for every result: rejected in favor of the immutable applied-session index and a bounded shared-UID lookup.

### Decision 3: Use a generation-qualified full flow key

Every record uses an immutable key:

```text
FlowKey(
  vpnGeneration,
  protocol,
  originalSourceAddress,
  originalSourcePort,
  originalDestinationAddress,
  originalDestinationPort
)
```

`vpnGeneration` increases whenever a new TUN/VPN session is successfully established. It prevents a delayed resolver or close callback from a stopped worker from changing the next session. The full five-tuple replaces the current three-part string as the statistics identity and leaves room for future address-family support.

Supporting data types are:

```text
AppIdentity = Resolved(uid, package, label)
            | SharedUid(uid, packages, labels)
            | Unknown

FlowRecord  = FlowKey + AppIdentity + protocol state
              + createdAt + lastActivityAt

AppStatsRow = AppIdentity + activeTcp + activeUdp + lastActivityAt

StatsSnapshot = generation + appliedPackages + rows
                + totalTcp + totalUdp + publishedAt
```

The records live only in memory and are discarded with their generation.

### Decision 4: Maintain records in a central tracker and derive totals from records

`CellularVpnService` creates one `ConnectionStatsTracker` per VPN generation and injects a narrow `FlowObserver` interface into TCP and UDP workers. The tracker owns a `ConcurrentHashMap<FlowKey, FlowRecord>` and exposes fast, idempotent operations:

```text
open(flowKey)
resolve(flowKey, identity)
touch(flowKey, direction, timestamp)
close(flowKey, terminalReason)
reset(generation)
```

`open` inserts only when absent, `close` removes only when present, and all operations reject a mismatched generation. This prevents duplicate SYN packets, repeated close paths, or old callbacks from producing negative counts.

A scheduled publisher folds current records into a new immutable `StatsSnapshot` at most once per second. TCP/UDP totals and APP rows are derived from the same map traversal rather than maintained as independent mutable counters, guaranteeing:

```text
row.total    = row.activeTcp + row.activeUdp
global.total = sum(all row totals)
```

The latest snapshot is stored in a process-local `AtomicReference` repository. `MainActivity`, notification rendering, and floating-window rendering only consume this snapshot; they no longer read `TCB` or UDP cache sizes directly.

**Alternatives considered:**
- Keep independent per-APP counters: rejected because duplicate and out-of-order lifecycle events can make counters drift or become negative.
- Have each UI surface inspect socket caches: rejected because it produces non-atomic and semantically different views.
- Introduce database persistence: rejected because active stats are session-only and persistence expands privacy and migration scope.
- Introduce coroutines/`StateFlow`: deferred to avoid adding a new concurrency dependency solely for a one-second in-process snapshot.

### Decision 5: Attach TCP identity to the TCB lifecycle

When an outbound SYN has no existing TCB, create the statistics record exactly once and store its `FlowKey` with the TCB. SYN retransmissions, ACKs, and data reuse that record. The primary count includes `SYN_SENT` and `SYN_RECEIVED`, so the UI wording remains “作用中連線” rather than “已建立連線”.

All terminal TCP paths converge on one idempotent close operation: connection failure, RST, completed bidirectional FIN, channel exception, cache eviction, and engine shutdown. The existing remote-EOF path must either finish the TCP close state machine or close the TCB; it may not leave an unobservable zero-interest TCB indefinitely. A single FIN remains active until terminal closure, matching the spec.

### Decision 6: Represent UDP as expiring associations

Replace the UDP cache value with a `UdpAssociation` containing the channel, reference packet, `FlowKey`, resolved identity, and last activity time. The first outbound datagram opens one record. Further inbound or outbound datagrams for the same flow call `touch` and do not increment the count.

A monotonic-clock sweeper marks an association inactive once it has had no traffic in either direction for 60 seconds and removes its statistics record. The association may be closed and evicted at the same time to prevent stale channels and source-port reuse from carrying an old APP identity; a later datagram creates and resolves a fresh association. Different destination endpoints remain distinct flows.

Use an injected clock in the tracker so the 60-second boundary can be tested without sleeping.

**Trade-off:** expiring the channel can change the upstream UDP source port after a long idle period. The selected 60-second definition favors accurate active counts and bounded resources; target APP UDP/QUIC behavior must be exercised in device tests.

### Decision 7: Treat the applied APP set as immutable session state

The selection stored by the Activity remains a draft until VPN establishment succeeds. During establishment, validate every selected package and retain only packages successfully added to the builder. If the resulting list is empty, abort before `establish()`; an empty allowed list must never be allowed to mean all applications.

Once established, the `AppliedAppIndex`, routing rules, and statistics generation remain immutable. Editing checkboxes does not rename existing rows or change routing. Applying a different set stops the old engine, clears its snapshot, establishes a new VPN, and publishes an empty snapshot for the new applied set. The UI should indicate that edits require reapplying/restarting the VPN.

### Decision 8: Use tiered rendering from one snapshot

The main screen adds an “作用中連線” section above the APP selector. It shows the summary and up to five rows ordered by total flow count, then recent activity, then localized label; an existing bottom-sheet pattern can expose all remaining rows. Each row displays label, total, TCP, and UDP. Duplicate labels add package text for disambiguation.

The notification displays total active APPs and flows, the top two resolved groups, and an “另 N 個” suffix. Notification rendering is throttled to once every five seconds unless the VPN starts/stops, cellular connectivity changes, the first flow appears, or the total reaches zero. Updates remain silent through `setOnlyAlertOnce(true)`.

The floating window is collapsed by default as “N APP · M 連線”. A deliberate tap, distinguished from drag movement, expands up to three rows and then automatically collapses. Main-screen and floating values update at most once per second and only when snapshot content changes; ordering is stabilized so equal counts do not jump every refresh.

The notification uses private visibility and a generic public version for the lock screen. While the device is locked, the floating window does not expose labels and returns to collapsed form after unlock.

### Decision 9: Test the tracker separately from Android ownership lookup

Keep tuple creation, owner resolution, package resolution, time, flow observation, and snapshot publication behind small interfaces. JVM tests use fake resolvers and a fake monotonic clock for aggregation, duplicate events, expiry, shared/unknown identity, and generation isolation. Android instrumentation/device tests verify the framework UID lookup with two real test packages and validate all three presentation surfaces.

## Risks / Trade-offs

- **[Owner lookup races with short-lived sockets]** → Create an `Unknown` record immediately, dispatch lookup once without blocking forwarding, and keep Unknown visible if the framework can no longer resolve the tuple.
- **[High connection churn fills the resolver queue]** → Use a bounded executor, deduplicate by `FlowKey`, cache resolved identity for the flow lifetime, and degrade overflow to Unknown instead of blocking packet workers.
- **[Shared or isolated UID cannot map to one package]** → Present one shared-UID or Unknown group and preserve totals rather than claiming false precision.
- **[UDP 60-second eviction affects dormant protocols]** → Test DNS, QUIC, and target APP behavior on Xiaomi devices; a later packet transparently establishes a new association.
- **[Concurrent shutdown produces late callbacks]** → Qualify all events with `vpnGeneration`, make close idempotent, cancel publishers/resolvers during shutdown, then publish an empty snapshot.
- **[Existing TCP close paths are incomplete]** → Route all terminal paths through one cleanup primitive and add lifecycle tests before trusting UI totals.
- **[APP labels in system UI reveal usage]** → Use a private notification with a generic lock-screen version and hide labels from the floating window while locked.
- **[Current engine only observes routed IPv4 TCP/UDP]** → Label metrics as VPN-observed active connections and keep IPv6 support outside this change.

## Migration Plan

1. Raise `minSdk` to 29 and add pure model/resolver/tracker interfaces with tests while retaining the existing total display.
2. Build the immutable applied-APP index and create a new tracker generation only after VPN establishment succeeds.
3. Integrate TCP lifecycle events and fix terminal cleanup; verify old global counts against tracker totals.
4. Integrate UDP associations, bidirectional activity refresh, and 60-second expiry.
5. Switch the main screen, notification, and floating window to the shared immutable snapshot and remove direct UI reads of static caches.
6. Run automated lifecycle/concurrency tests and Xiaomi multi-APP device scenarios before release.

No persistent data migration is required. Rollback consists of restoring the previous APK/code path; existing SharedPreferences selections remain compatible. Devices below API 29 cannot install the new release and must remain on the previous version.
