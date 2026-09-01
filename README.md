# Wayfarer

A small Android nostr client with full outbox support, where every relay connection is
explicitly approved by the user.

## Features

- Create an account, or log in with an `npub` (watch-only), an `nsec`, or an external signer app.
- View and publish profiles, short notes, and long-form articles.
- **Outbox routing (NIP-65)** — notes are read from the relays their authors publish to, and
  published to your write relays plus the read relays of everyone you mention.
- **Deny-by-default relay permissions** — no socket opens to a relay you have not approved. Read
  and write are granted separately, and relays the app wanted are queued with the reason why.

## Architecture

```
core/          All app logic, as a Kotlin Multiplatform module. Depends on the Kotlin
               stdlib and kotlinx-coroutines, and nothing else — no nostr library,
               no JSON library, no Android, no UI.
nostr-quartz/  The only module that imports Quartz. Implements core's interfaces.
app/           Android application: Compose UI, view models, platform storage.
```

Dependencies run strictly `app → nostr-quartz → core`. The core declares `NostrCodec`,
`Bech32Codec`, `KeyTool`, `RelayTransport`, `RelayInfoFetcher` and `EventSigner` as interfaces;
`nostr-quartz` implements them. `grep -r vitorpamplona core/ app/` returns nothing.

## Relay approval

`RelayDirectory` is the single authority on what a relay may do. Every relay set the app builds
passes through it, and rejected relays are recorded as *pending* with the reason they were wanted
("write relay of npub1abc…", "your read relay"), so the approval queue reflects your own social
graph rather than a list the app invented.

The gate is enforced twice: at routing, where `OutboxRouter` will not name an unapproved relay in
any plan, and at the socket, where `GatedWebsocketBuilder` refuses to dial one. The second check
never fires in normal operation — it exists because a promise enforced only by the code that
computes relay sets is one routing bug away from being false.

Reading a relay's NIP-11 document is an HTTPS request to that relay, so it is treated as a separate
consent: it happens only when you tap "Fetch relay info", and for a relay with no grant a dialog
names the host first.

## Outbox

- Read an author's events from their advertised *write* relays; each relay is asked only for the
  authors it serves.
- Publish to your write relays **and** each mentioned user's *read* relays. Mentions are parsed
  from the note text.
- Relay selection is a greedy set cover (`RelayCoverage`) with a redundancy target and a relay
  budget, preferring already-connected relays on a tie.
- Gaps are shown rather than hidden: authors with no approved relay are reported unreachable, and
  authors with no relay list at all are marked as guessed rather than routed.

## NIPs

| NIP | What | Extent |
|---|---|---|
| [01](https://github.com/nostr-protocol/nips/blob/master/01.md) | Events, ids, signatures, `REQ`/`EVENT`/`EOSE`/`OK`/`CLOSED`, filters | Full for the kinds used. Every incoming event is id- and signature-verified. |
| [02](https://github.com/nostr-protocol/nips/blob/master/02.md) | Follow list (kind 3) | Read only; drives whose notes the feed asks for. |
| [10](https://github.com/nostr-protocol/nips/blob/master/10.md) | Threading | Reply targets parsed from marked `e` tags with positional fallback. No thread view. |
| [11](https://github.com/nostr-protocol/nips/blob/master/11.md) | Relay information | Fetched on explicit request; shows supported NIPs, software, auth/payment requirements, posting policy. |
| [19](https://github.com/nostr-protocol/nips/blob/master/19.md) | bech32 entities | `npub`/`nsec` encode and decode, `nprofile` decode, `note` encode. |
| [21](https://github.com/nostr-protocol/nips/blob/master/21.md) | `nostr:` URIs | Accepted wherever a key is parsed, and when scanning notes for mentions. |
| [23](https://github.com/nostr-protocol/nips/blob/master/23.md) | Long-form content (kind 30023) | Read and authoring. Addressable: the `d` tag is preserved across edits so a revision replaces rather than duplicates. Markdown bodies render as plain text. |
| [55](https://github.com/nostr-protocol/nips/blob/master/55.md) | Android signer application | Intent transport only (`get_public_key`, `sign_event`). The Content Resolver transport exists for background signing, which this app never does. |
| [65](https://github.com/nostr-protocol/nips/blob/master/65.md) | Relay list metadata (kind 10002) | Full, and central. Read for every author routed to; published from your approved relay grants. |

Planned: **NIP-46** (remote `bunker://` signing). Quartz already provides the client, and it would
be a second `EventSigner` — but a bunker URI carries relay URLs, so those relays have to go through
the approval gate like any other.

Not implemented: NIP-42 (relay auth — so auth-gated relays will refuse reads), NIP-05 verification,
NIP-04/17/59 (direct messages), NIP-25 (reactions), NIP-18 (reposts), NIP-57 (zaps), NIP-09
(deletions), NIP-50 (search), NIP-51 (lists), NIP-77 (negentropy).

## What is used from Quartz

`com.vitorpamplona.quartz:quartz:1.14.0`, from Maven Central. It is a real KMP library (android,
ios, jvm, linuxX64, macosArm64). Everything the project takes from it lives in `nostr-quartz/`:

| Quartz | Used for | Replacing it means |
|---|---|---|
| `nip01Core.crypto.Nip01Crypto`, `KeyPair`, `signers.NostrSignerInternal` | Key generation, pubkey derivation, signing | A CSPRNG and BIP-340 schnorr over the NIP-01 serialization |
| `nip01Core.crypto.verify`, `core.Event` | Event type, id + signature verification, JSON | Your own event type, SHA-256 of the canonical form, schnorr verify |
| `nip01Core.metadata.MetadataEvent` | kind 0 parse; `updateFromPast` preserves unknown JSON fields on edit | JSON parse/merge of the profile object |
| `nip02FollowList.ContactListEvent` | Validated kind 3 `p` tags | Reading and validating `p` tags |
| `nip19Bech32` (`toNpub`/`toNsec`/`toNote`, `decode*AsHexOrNull`) | NIP-19 | A bech32 implementation |
| `nip23LongContent.LongTextNoteEvent` | kind 30023 read and build, `d`/title/summary/image tags | Reading and writing those tags |
| `nip65RelayList.AdvertisedRelayListEvent` + tags | kind 10002 `r`-tag grammar | Reading/writing `["r", url, "read"\|"write"?]` |
| `nip01Core.relay.normalizer.RelayUrlNormalizer` | Canonical relay URLs | **See the note below** |
| `nip01Core.relay.client.NostrClient` + `fetchAllWithHooks`, `publishAndCollectResults` | Relay pool, per-relay REQ, per-relay `OK` results | A websocket pool speaking the NIP-01 client protocol |
| `nip01Core.relay.sockets.*` + `okhttp.BasicOkHttpWebSocket` | The socket abstraction the approval gate hooks into | Any socket interface with a `canConnect` veto |
| `nip11RelayInfo.OkHttpNip11Fetcher`, `CachedNip11Fetcher` | NIP-11 documents | An HTTP GET with `Accept: application/nostr+json` |
| `utils.TimeUtils` | `created_at` | `System.currentTimeMillis() / 1000` |

**On `RelayUrlNormalizer`** — this is not a formatting helper. Relay permission is keyed by URL
equality, so if `wss://Relay.example` and `wss://relay.example/` do not collapse to one key, a user
can approve one spelling and unknowingly connect to the other. Quartz's normalizer also anchors its
`.onion` and localhost checks to the URL authority rather than the whole string, so
`wss://evil.example.com/127.0.0.1` does not read as localhost. Any replacement must get this right.

Quartz's transitive graph is large relative to this project's goals: jackson, OkHttp,
secp256k1-kmp with JNI natives, androidx.sqlite plus a bundled SQLite, kotlinx-serialization
(json and cbor), collections-immutable, negentropy, and `kchesslib` for NIP-64. None of the
storage, sync or chess parts are used. The interfaces in `core/nostr/` are the exit: a replacement
backend would cut the dependency list to a schnorr implementation and a websocket client.

Beyond Quartz the app adds only Compose, androidx activity/lifecycle/core-ktx and OkHttp. There is
no navigation library, image loader, DI framework, markdown renderer, or JSON library in `core`.
The account key is encrypted with an `AndroidKeyStore` AES-GCM key in about sixty lines of standard
JCA rather than depending on the deprecated `androidx.security:security-crypto`. The app requests
one permission: `INTERNET`.

## Building

```sh
./gradlew :app:assembleDebug
./gradlew test
```

Requires JDK 17 and an Android SDK with API 36. `google()` is needed as a repository even for the
non-UI modules, because Quartz depends on androidx artifacts published only there.

## Status

`core`, `nostr-quartz`, the view models and the Compose UI are compile-verified, with 86 unit tests
covering the relay gate, the set-cover router, NIP-11 consent, NIP-23 addressable replacement, the
NIP-55 wire format, the controller wiring, and round-trips through real secp256k1 signing and real
Quartz parsing.

The full Android build has not been run: `dl.google.com` was unreachable from the machine this was
written on, so no androidx artifact or Android SDK could be fetched. The modules were verified by
compiling against `quartz-jvm` and Compose Multiplatform, which mirror the same APIs, on a plain
JVM. Five files touching Android-only APIs are therefore unverified — `MainActivity`,
`WayfarerApplication`, `AndroidStores`, `Nip55Bridge` and `AppSignerFactory` — as are the AGP,
`compileSdk` and Compose BOM versions in `gradle/libs.versions.toml`.

## License

MIT, matching Quartz.
