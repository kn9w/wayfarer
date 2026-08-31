# Wayfarer

A small Android nostr client, built to demonstrate two things properly rather than many things
partially:

1. **Full outbox support (NIP-65).** Notes are read from the relays their authors say they publish
   to, and published to your write relays *plus* the read relays of everyone you mention.
2. **Every relay connection is user-approved.** Nothing is a default. No socket is opened to any
   relay the user has not explicitly allowed, and read and write are granted separately.

It also does the basics: create an account, log in with an npub or nsec, view and publish profiles,
view and publish notes.

## Layout

```
core/          Kotlin Multiplatform. All app logic. No UI, no Android, no Quartz, no JSON library.
               Its only dependency beyond the Kotlin stdlib is kotlinx-coroutines.
nostr-quartz/  Kotlin Multiplatform. The only module that imports Quartz. Implements core's
               nostr SPI and nothing else.
app/           Android application. Compose UI, view models, and two platform storage classes.
```

The direction of dependency is strictly `app → nostr-quartz → core`. `core` names no nostr library
at all: it declares small interfaces (`NostrCodec`, `Bech32Codec`, `KeyTool`, `RelayTransport`,
`EventSigner`, `RelayUrlNormalizer`) and `nostr-quartz` implements them.

Grepping for `vitorpamplona` outside `nostr-quartz/` returns nothing. That is the property that
makes the backend replaceable.

## The relay approval gate

`RelayDirectory` (in `core/relay/`) is the single authority on what a relay may do. It is
deny-by-default: a relay with no grant cannot be read from or written to.

Every relay set the app builds passes through it, and every relay that gets rejected is recorded in
a **pending** queue together with *why* it was wanted — "write relay of npub1abc…", "your read
relay", "suggested by the app". So the approval queue fills up with exactly the relays the user's
own social graph points at, rather than a list the app invented. The Relays screen shows three
sections: approved (with independent Read and Write switches), awaiting approval, and refused.

The gate is enforced **twice**, on purpose:

- **At routing.** `OutboxRouter` asks `RelayDirectory` before naming any relay in a plan, so an
  unapproved relay never appears in a REQ or a publish target set.
- **At the socket.** `GatedWebsocketBuilder` (in `nostr-quartz/`) decorates Quartz's websocket
  builder and refuses to open a connection to any URL the directory does not approve. In normal
  operation it never fires. It exists because "no connection to an unapproved relay" is the app's
  central promise, and a promise enforced only by the code that computes relay sets is one routing
  bug away from being false.

The five relays in `Wayfarer.BOOTSTRAP_SUGGESTIONS` are *suggestions*: on a fresh install they land
in the pending queue like anything else. Until the user approves one, the app connects to nothing.

## How the outbox model is implemented

- **Reading someone's notes** uses their advertised *write* relays (`RelayList.outbox`).
- **Publishing** goes to your own write relays, plus the *read* relays of every pubkey you mention.
  Skipping that second half is the most common way clients silently break mentions.
- **Mentions** are parsed out of the note text (`nostr:npub1…` or a bare `npub1…`), which both
  produces the `p` tags and pulls those users' inbox relays into the target set.
- **Relay selection** is a greedy set cover (`RelayCoverage`): rather than connecting to every relay
  every follow advertises, it repeatedly picks the relay serving the most authors still short of the
  redundancy target, until every author is covered twice or the relay budget runs out. Already-
  connected relays win ties, so a refresh reuses sockets. Each relay is then asked only for the
  authors it is responsible for.
- **Gaps are shown, not hidden.** Authors whose relays are not approved are reported as unreachable
  in the feed. Authors with no kind 10002 at all are fetched from your approved read relays and the
  UI says so — that is a guess, not outbox routing. `OutboxConfig.fallbackToApprovedReadRelays =
  false` turns the guess off and reports them as unreachable instead.
- **Publishing reports per relay.** After a publish the UI lists which relays accepted, which
  refused and their reason, and how many of the targets were mention inboxes rather than your own.

## NIPs

### Covered

| NIP | What | Extent |
|---|---|---|
| [01](https://github.com/nostr-protocol/nips/blob/master/01.md) | Basic protocol: event format, ids, schnorr signatures, kinds 0 and 1, `REQ`/`EVENT`/`CLOSE`/`EOSE`/`OK`/`NOTICE`/`CLOSED`, filters | Full for the kinds used. Every incoming event is id- and signature-verified before it reaches the note store. |
| [02](https://github.com/nostr-protocol/nips/blob/master/02.md) | Follow list (kind 3) | Read only. Used to decide whose notes the home feed asks for. Wayfarer does not edit follows. |
| [10](https://github.com/nostr-protocol/nips/blob/master/10.md) | Text note threading | Partial. Reply targets are parsed from marked `e` tags (`reply`, then `root`) with a fallback to the deprecated positional form; replies are written with a `root`-marked `e` tag. No thread view. |
| [19](https://github.com/nostr-protocol/nips/blob/master/19.md) | bech32 entities | Partial. `npub` and `nsec` encode/decode, `nprofile` decode, `note` encode. Login accepts any of those or raw hex. |
| [21](https://github.com/nostr-protocol/nips/blob/master/21.md) | `nostr:` URI scheme | Partial. Accepted and stripped wherever a key is parsed, and recognised when scanning a note for mentions. |
| [65](https://github.com/nostr-protocol/nips/blob/master/65.md) | Relay list metadata (kind 10002) | Full, and central. Read for every author the app routes to, and published for your own account from the approved relay grants — read grants become read relays, write grants become write relays. |

### Deliberately not covered

Quartz implements roughly ninety NIPs; this app uses six. Not implemented, in rough order of how
much their absence is felt:

- **NIP-42** (relay auth) — Quartz's client supports it, but no authenticator is attached, so
  auth-gated relays will refuse this client's reads.
- **NIP-05** (DNS identifiers) — the field is stored and displayed, never verified.
- **NIP-25** (reactions), **NIP-18** (reposts), **NIP-23** (long form), **NIP-57** (zaps).
- **NIP-04** / **NIP-17** / **NIP-59** (direct messages, gift wrap).
- **NIP-46** / **NIP-55** (remote and external signers) — see the note under `EventSigner` below.
- **NIP-49** (encrypted private keys) — the secret key is instead encrypted at rest with an Android
  keystore key.
- **NIP-11** (relay information documents), **NIP-50** (search), **NIP-77** (negentropy sync),
  **NIP-09** (deletions), **NIP-51** (lists).

## What is used from Quartz

The dependency is `com.vitorpamplona.quartz:quartz:1.14.0` from Maven Central. Quartz is a real
Kotlin Multiplatform library (android, ios, jvm, linuxX64, macosArm64), so nothing here is Android-
specific by necessity.

Everything the app takes from it, in full — this is the complete list of Quartz symbols the project
references, and they all live in `nostr-quartz/`:

| Quartz | Used for | Wayfarer file | Replacing it means |
|---|---|---|---|
| `nip01Core.crypto.Nip01Crypto` | 32 random bytes for a new key; x-only pubkey derivation | `QuartzCrypto.kt` | A CSPRNG and secp256k1 pubkey derivation |
| `nip01Core.crypto.KeyPair`, `signers.NostrSignerInternal` | Signing events with a local key | `QuartzSigner.kt` | BIP-340 schnorr signing over the NIP-01 serialization |
| `nip01Core.crypto.verify` (on `Event`) | Verifying id + signature of every incoming event | `QuartzNostrCodec.kt` | SHA-256 of the canonical serialization, plus schnorr verify |
| `nip01Core.core.Event`, `.toHexKey`, `.hexToByteArray` | The wire event type and hex helpers | `QuartzEventMapping.kt` | Your own event type plus hex codecs |
| `nip01Core.signers.EventTemplate` | Unsigned event carrier returned by Quartz's builders | `QuartzNostrCodec.kt` | Nothing — it is just (kind, content, tags, createdAt) |
| `nip01Core.metadata.MetadataEvent` | kind 0 parse; `createNew` / `updateFromPast` to build one while preserving unknown JSON fields | `QuartzNostrCodec.kt` | JSON parse/merge of the kind 0 content object |
| `nip02FollowList.ContactListEvent` | `verifiedFollowKeySet()` — kind 3 `p` tags, validated | `QuartzNostrCodec.kt` | Reading and validating `p` tags |
| `nip19Bech32.toNpub` / `toNsec` / `toNote`, `decodePublicKeyAsHexOrNull`, `decodePrivateKeyAsHexOrNull` | NIP-19 encode/decode | `QuartzCrypto.kt` | A bech32 implementation |
| `nip65RelayList.AdvertisedRelayListEvent`, `tags.AdvertisedRelayInfo`, `tags.AdvertisedRelayType` | kind 10002 `r`-tag grammar, both directions | `QuartzNostrCodec.kt` | Reading and writing `["r", url, "read"\|"write"?]` |
| `nip01Core.relay.normalizer.RelayUrlNormalizer` / `NormalizedRelayUrl` | Canonical relay URLs | `QuartzCrypto.kt`, `QuartzEventMapping.kt` | **Read the note below — this one matters** |
| `nip01Core.relay.client.NostrClient` | The relay pool: per-relay REQ, reconnects, publish tracking | `QuartzRelayTransport.kt` | A websocket pool speaking the NIP-01 client protocol |
| `…client.accessories.fetchAllWithHooks` | One-shot fetch that ends on EOSE and keeps the delivering relay | `QuartzRelayTransport.kt` | Collect until EOSE per relay, with an idle timeout |
| `…client.accessories.publishAndCollectResults` | Per-relay `OK` results with reasons | `QuartzRelayTransport.kt` | Match `OK` frames to a published event id |
| `…client.reqs.SubscriptionListener`, `…client.single.newSubId` | Subscription callbacks and id generation | `QuartzRelayTransport.kt` | Trivial |
| `nip01Core.relay.filters.Filter` | The wire filter type | `QuartzRelayTransport.kt` | JSON serialization of a NIP-01 filter |
| `nip01Core.relay.sockets.WebsocketBuilder` / `WebSocket` / `WebSocketListener` | The socket abstraction the approval gate hooks into | `GatedWebsocketBuilder.kt` | Any socket interface with a `canConnect` veto |
| `…sockets.okhttp.BasicOkHttpWebSocket.Builder` | The OkHttp websocket implementation | `QuartzRelayTransport.kt` | Any websocket client |
| `utils.TimeUtils` | `created_at` in seconds | `QuartzCrypto.kt` | `System.currentTimeMillis() / 1000` |

**On `RelayUrlNormalizer`:** it is easy to read this as a formatting helper, and it is not. Relay
permission is keyed by `RelayUrl` equality, so if `wss://Relay.example` and `wss://relay.example/`
do not collapse to the same key, a user can approve one spelling and unknowingly connect to the
other. Quartz's normalizer also anchors its `.onion` and localhost checks to the URL authority
rather than the whole string — a substring test there means `wss://evil.example.com/127.0.0.1`
reads as localhost. Any replacement has to get this right.

**On `EventSigner`:** the interface exists mainly for this. Quartz ships NIP-46 (remote bunker) and
NIP-55 (external Android signer) implementations behind the same base class, so adding either is a
second implementation of `EventSigner` in `nostr-quartz/`, with nothing above that file changing.

### What Quartz drags in

Worth stating plainly, since the goal is fewest dependencies. `quartz` transitively brings jackson,
OkHttp, secp256k1-kmp (with JNI natives), androidx.sqlite plus a bundled SQLite, androidx.collection,
kotlinx-serialization (json *and* cbor), kotlinx-collections-immutable, a negentropy implementation,
and `kchesslib` — a chess library, for NIP-64. Wayfarer uses none of the storage, sync, or chess
parts; they arrive anyway.

That is the honest trade. Quartz supplies careful, widely-exercised implementations of the fiddly
parts (canonical event serialization, bech32, relay URL normalization, a relay pool with reconnect
and OK tracking) at the cost of a large transitive graph. The SPI in `core/nostr/` is the exit: a
replacement backend implementing those five interfaces would cut the dependency list to a schnorr
implementation and a websocket client.

## Dependencies

| Module | Depends on |
|---|---|
| `core` | Kotlin stdlib, kotlinx-coroutines-core. That is all. |
| `nostr-quartz` | `core`, `quartz`, OkHttp (Quartz exposes it only at runtime, but this module names the OkHttp websocket builder directly) |
| `app` | `core`, `nostr-quartz`, Compose (ui, material3), androidx activity/lifecycle/core-ktx |

Notably absent: no navigation library (six screens and a `when`), no image loader, no dependency
injection framework, no JSON library in `core`, no `androidx.security:security-crypto` — the secret
key is encrypted with about sixty lines of standard JCA against `AndroidKeyStore`, since that
library is deprecated and this is not much code.

The app requests exactly one Android permission: `INTERNET`.

## Key storage

The account secret key is encrypted with AES-256-GCM under a key generated in, and never leaving,
`AndroidKeyStore` (`AndroidSecretStore` in `app/platform/`). The ciphertext on disk is useless
without that key. An attacker running code *as this app* on an unlocked device can still ask the
keystore to decrypt; requiring device authentication per decryption is a one-line change to the
`KeyGenParameterSpec`, marked in the source. Backup and device transfer are disabled for the app's
files.

## Building

```
./gradlew :app:assembleDebug
./gradlew test
```

Requires JDK 17 and an Android SDK with API 36. `google()` is required as a repository even for the
non-UI modules, because Quartz depends on androidx artifacts that are published only there.

## Verification status

`core`, `nostr-quartz` and the view models are compile-verified and covered by 48 unit tests,
including the relay gate, the set-cover router, the permission codec, and round-trips through real
secp256k1 signing and real Quartz NIP-01/02/19/65 parsing. The Compose UI is compile-verified.

Two caveats, both from the machine this was authored on rather than the code:

- **The full Android build has not been run.** `dl.google.com` was unreachable there, so neither
  the Android SDK nor any androidx artifact could be fetched. The modules were verified by
  compiling their sources against `quartz-jvm` and Compose Multiplatform (which mirror the same
  APIs) on a plain JVM. `MainActivity`, `WayfarerApplication` and `AndroidStores` — the three files
  that touch Android-only APIs — are the only sources no compiler has seen.
- **The AGP, compileSdk and Compose BOM versions in `gradle/libs.versions.toml` could not be
  checked against Google's Maven** and may need bumping. They are the only unverified versions in
  the catalog, and they are all in one file.

## Licence

MIT, matching Quartz.
