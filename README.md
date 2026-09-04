# Wayfarer

A small Android nostr client with full outbox support, where every relay connection is
explicitly approved by the user.

## Features

- **Onboarding that explains itself** — "New to nostr?" leads to three plain-language screens on
  keys and relays, then offers an account and an equally weighted "not for now". Reading needs no
  key, so browsing without an account is a supported way to use the app rather than a dead end.
- Create an account, or log in with an `npub` (watch-only), an `nsec`, or an external signer app.
- **Your key stays reachable, and stays off the screen record** — the `nsec` is encrypted by an
  `AndroidKeyStore` key, shown once at setup on a screen with no way to leave it by accident, and
  readable again in Settings behind the device's own lock screen. Both screens that can show it set
  `FLAG_SECURE`, so it is kept out of screenshots, screen recordings and the task switcher's
  snapshot — a lock screen in front of the key is worth little if backgrounding the app hands the
  same key to the recents thumbnail — and leaving the app puts it away again.
- View and publish profiles, short notes, and long-form articles — NIP-23 markdown is rendered
  rather than shown with its marks in, and an article says both when it was published and when it
  was last changed.
- **Outbox routing (NIP-65)** — notes are read from the relays their authors publish to, and
  published to your write relays plus the read relays of everyone you mention.
- **A follow list that stays on this phone** — an alternative to the public kind 3, for people you
  want to read without announcing it. It is never published, so no other client can see it. It is
  not invisible to *relays*: reading somebody means asking a relay for their posts by pubkey, so the
  relays you read through still learn who is on it. The Following screen says so where the two kinds
  of follow are explained.
- **Deny-by-default picture servers** — the same rule for media. Nothing is fetched from an image
  host you have not allowed; hosts queue themselves as you read, with the reason each was wanted,
  and the header says how many are waiting.
- **Deny-by-default relay permissions** — no socket opens to a relay you have not approved. Read
  and write are granted separately, and relays the app wanted are queued with the reason why. The
  permission list is local to the device: changing it publishes nothing.
- **Your NIP-65 relay list, edited where it belongs** — a "where others find you" section on your
  own profile, with its own screen for the public kind 10002: what is advertised, what each entry
  means, what becomes public, and a *Why does this matter?* explainer. Publishing is always an
  explicit press.
- **Nothing is queried behind your back** — whenever the app would have to fall back to the relays
  it ships with, it names them first and offers a field to use one of your own instead. Starting
  points can be typed or scanned from a QR code.

## Architecture

```
core/          All app logic, as a Kotlin Multiplatform module with a JVM target and
               no Android target — it has no Android source. Depends on the Kotlin
               stdlib and kotlinx-coroutines, and nothing else — no nostr library,
               no JSON library, no Android, no UI.
nostr-quartz/  The only module that imports Quartz. Implements core's interfaces.
               An Android library: everything in it is Android-only.
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

**This list belongs to whoever is signed in.** It is keyed by the account that granted it: signing in
as somebody else starts with nothing allowed, switching between signed-in accounts swaps one list
for the other, and logging out erases it — see *Accounts*. A session with nobody signed in gets one that lives for the session and is never written down —
reading without an account is supported, so the permissions have to work, but a consent record for
nobody is not a thing to keep, and the next launch would otherwise inherit relays this session never
approved. There is deliberately no migration from the device-wide list earlier builds kept: that
list was the consent of whoever was holding the phone, which is not a fact about any account.

**This list is local to the app.** It is not a NIP-65 relay list, it is not an event, and approving
or blocking a relay publishes nothing and tells nobody. Advertising your relays to the network is a
separate, explicitly requested action — see *Outbox* below — and the screen says so where the
buttons are. (If that list ever becomes shared between a user's own devices, the natural shape is an
encrypted private event, which is still not the same thing as NIP-65.)

Because permissions go with an account, a session that may reach nothing is a normal state rather
than a first-run one — so *Where should we start?* is asked whenever it happens: on a first launch,
on every launch of a guest session, and on an account's first sign-in. A returning account with a
stored list goes straight into the app.

Nothing is contacted while onboarding is on screen — not the live subscription, and not the
one-shot loads either. That distinction was a real bug rather than a nicety: back when grants were
one device-wide record, the *second* time somebody reached "Where should we start?" the app already
had relays it was allowed to talk to, and the load behind a freshly created account went and used
them, under a screen saying nothing had been contacted yet. A sign-in that happens during onboarding
now waits for onboarding to end, and is then run in full.

Approving a relay reloads whatever is on screen against the new permission. It is the one thing
standing between a new user and the posts they are waiting for, so it is not left to them to
discover that a Refresh button now does something different.

## Accounts

Several can be signed in at once, and one is active. Nostr identities are cheap and people keep
more than one on purpose — a name, a pseudonym, a project — so an app that holds a single account
makes using the second mean destroying the first, which for an account whose key lives here means
erasing the only copy of that key. `AccountManager` keeps a roster of who is signed in and
materialises a credential only for the active one, so the keys of accounts nobody is currently using
are not sitting decrypted in memory. Each key is stored under its own owner's id; the single slot
older builds wrote is migrated across, because a key is the one thing a storage change may never
silently drop.

Switching is not signing out: nothing is erased, and the account being left keeps its key, its
permissions and its follows. Every connection is dropped first, because the sockets that are open
belong to the account that opened them.

**Logging out is a departure: nothing of that account stays on the phone.** Its key, both permission
lists, the follow list kept on this phone, and every picture already fetched while it was signed in.
The permissions go because a relay grant and a picture-server grant are standing permission to open a
connection, and leaving them would mean the next person to sign in with that key resumes talking to
those servers without being asked. The private follow list goes for the opposite reason: nothing
about it is public, so nobody could ever discover it had been left behind. The pictures go because
they are a legible trace of who that account was reading, and because a cache hit is answered before
the permission gate is consulted. The dialog itemises all of it before anything happens, since
"log out" does not obviously mean any of those things. Another signed-in account takes over if there
is one; otherwise the session is left connected to nothing, at the front door.

Switching accounts erases none of it — that is the difference between leaving and putting something
down.

## The two relay lists

They are easy to confuse and mean opposite things, so the app keeps them in separate screens, with
separate view models, and says on each what the other is.

| | Relay permissions | Relay list (NIP-65) |
|---|---|---|
| Question it answers | What may this app connect to? | Where should people look for me? |
| Where it lives | This phone, in app settings | A signed kind 10002 on the network |
| Who can see it | Nobody | Anyone |
| Reached from | The Relays tab | Your own profile → *Where others find you* |
| Changing it | Opens or closes connections here | Publishes nothing until you press Publish |

The editor is a draft until published, and says so: with no kind 10002 yet, it offers the relays
you allow here as a *starting point* rather than presenting them as a fact about you. A relay can
be in one list and not the other — advertising a relay this app may not reach is legal and usually
a mistake, so the row says so and offers to allow it here too, one relay at a time. There is no
automatic bridge in either direction.

Someone else's advertised list is shown on their profile as well, from the cache outbox routing
already fills — no extra fetch, and it is the thing that explains why their posts are reachable or
guessed at.

The gate is enforced twice: at routing, where `OutboxRouter` will not name an unapproved relay in
any plan, and at the socket, where `GatedWebsocketBuilder` refuses to dial one. The second check
never fires in normal operation — it exists because a promise enforced only by the code that
computes relay sets is one routing bug away from being false.

Reading a relay's NIP-11 document is an HTTPS request to that relay, so it is treated as a separate
consent: it happens only when you tap "Fetch relay info", and for a relay with no grant a dialog
names the host first.

## Pictures, and the servers they come from

A picture in a post is not part of the post. It is a link to somebody's web server, and showing it
means fetching it from there — handing that server your IP address and the fact that you are
reading this post, now. It is a different party from the relay the post came from, and it was
chosen by whoever wrote the post rather than by you. An app that gates relay sockets and then loads
every avatar it is pointed at has gated the smaller half.

So media hosts get their own deny-by-default list with the relay list's shape — including whose it
is: like the relay permissions, it belongs to the account that granted it, is never written down for
a guest, and is erased when that account logs out. `MediaDirectory` is the single authority, and
`GatedImageRequests` enforces it a second time as an OkHttp interceptor —
registered as both an application and a network interceptor, so neither the disk cache nor a
redirect can route around it. Nothing is suggested at first run, because with no approved host the
app still works: it draws a mark from the author's key instead of a photograph.

**The queue fills itself.** Every profile, article and post the app takes in records the hosts it
points at, with the reason each was wanted — "the picture of npub1abc…", "a picture in a post by
alice" — so the list on the Pictures screen is one your own reading built. Pressing an undrawn
picture does not nominate its server; it goes to the decision, because the server is already
waiting there. The picture icon in the header carries a pill with how many are.

Faces and banners appear on a person's own profile and nowhere else. A byline names its author — a
display name, or their npub shortened — because an avatar on every row is a request to a stranger's
server per post, forty of them down a feed, for a reader who came to read the words.

**A picture that is drawn can be opened.** Tapping one fills the window, pinch-zooms to six times
its fitted size and pans while zoomed; the fetch is the same gated `ImageLoader` call the small
version made, so opening a picture cannot reach a host the small one could not. A video from an
allowed host plays full-window in the platform's own `VideoView` — no media dependency, and never
started by scrolling past it: the still carries a play button, and the tap is the request. A video
whose host is undecided is still only named, with the badge that leads to that decision.

Video is queued and decided about like anything else and then named rather than played: Wayfarer
has no player, and an honest line beats a frame that never starts.

## When something goes wrong

Errors are snackbars, not a banner. Every message used to be the same full-width card pinned under
the app bar: it pushed the page down, stayed until dismissed, and gave a mistyped relay address the
same weight as a publish report naming eight relays. Transient messages now float over the content,
leave on their own, and move nothing; an error is tinted and stays a little longer, because it is
the one worth reading twice. A publish report is not transient — it is a record to read, per relay —
so it keeps the card.

And where the fault is with something typed, it is said next to the field that produced it rather
than at the top of the screen: the key on the sign-in screen, the starting point, a relay or picture
server added by hand. The controller answers "what is wrong with this?" without acting on it, so the
check happens before anything is attempted and the message clears the moment the text changes.

## Two colours

Every action in this app is one of two things, and which one it is matters more than anything else
on the screen it sits on: it either signs something and hands it to other people's servers, or it
changes a list that never leaves this phone. The app said so in prose on every screen and then drew
both in the same blue, so the distinction it is built around was the one thing you had to read a
paragraph to learn.

**Moss green is public. Trail brown is local.** Following publicly, replying, publishing a note, an
article, a profile, a relay list or a payment address are green — so is the `+` that starts one, and
so is the progress bar, on a brown track. Allowing a relay, allowing a picture server, following on
this phone, logging out, and the connection dot are brown. The two tabs are the same pair, because
Global and Local *are* the distinction. They live in `Theme.kt` as `publicAccent` and `localAccent`
so a screen names the meaning rather than the colour.

Moss *is* Material's `primary` rather than a colour beside it. Primary is what the library spends on
every default button, switch, selection and progress bar, so a public accent that was not primary
would have been contradicted by every control nobody had got round to tinting by hand. Compass blue,
which used to be primary, keeps `tertiary` for the rare accent that is neither public nor local.

Colour is never the only signal. The wording still says what happens, the relay and picture screens
keep their glyphs, and the coverage badge on a profile carries a tick, a half-tick or a cross —
because green against brown is precisely the pair a red-green colour-blind reader separates worst.

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
| [01](https://github.com/nostr-protocol/nips/blob/master/01.md) | Events, ids, signatures, `REQ`/`EVENT`/`EOSE`/`OK`/`CLOSED`, filters | Full for the kinds used. Every incoming event is id- and signature-verified before it is stored — notes, articles and thread comments, and equally the kinds that steer the app: profiles (0), follow lists (3), relay lists (10002) and payment targets (10133). |
| [02](https://github.com/nostr-protocol/nips/blob/master/02.md) | Follow list (kind 3) | Read only; drives whose notes the feed asks for. Verified before use. |
| [10](https://github.com/nostr-protocol/nips/blob/master/10.md) | Threading | Reply targets parsed from marked `e` tags with positional fallback. A conversation is read by its root: replies of both conventions, plus the post they answer, fetched by id so a thread opened from a reply has a beginning. Every reply in it can be replied to, and what is being answered is named and quoted in the composer rather than left to be inferred. |
| [11](https://github.com/nostr-protocol/nips/blob/master/11.md) | Relay information | Fetched on explicit request; shows supported NIPs, software, auth/payment requirements, posting policy. |
| [19](https://github.com/nostr-protocol/nips/blob/master/19.md) | bech32 entities | `npub`/`nsec` encode and decode, `nprofile` decode **with its relay hints kept**, `note` encode. Hints are offered for approval, never used on the strength of the link alone. |
| [21](https://github.com/nostr-protocol/nips/blob/master/21.md) | `nostr:` URIs | Accepted wherever a key is parsed, and when scanning notes for mentions. Written, too: "Copy event id" puts `nostr:note1…` on the clipboard rather than bare hex, because that is the form another client can resolve. |
| [23](https://github.com/nostr-protocol/nips/blob/master/23.md) | Long-form content (kind 30023) | Read and authoring, with the markdown rendered — headings, emphasis, code, quotes, lists, and pictures where the author put them. Addressable: `d`, `published_at` and the `t` topics are all preserved across an edit, so a revision replaces rather than duplicates and keeps the date the article first appeared. Soft wraps are reflowed, as the NIP requires of anyone writing one. |
| [55](https://github.com/nostr-protocol/nips/blob/master/55.md) | Android signer application | Intent transport only (`get_public_key`, `sign_event`). The Content Resolver transport exists for background signing, which this app never does. |
| [57](https://github.com/nostr-protocol/nips/blob/master/57.md) | Zaps | The address only. A profile's `lud16` is read, shown, and edited in the profile form with the website — it is a field of the kind 0, so it publishes with it, which is what keeps it out of the separate NIP-A3 section below. Nothing here requests an invoice or pays one — the app publishes where you take payment, and leaves paying to a wallet. |
| [65](https://github.com/nostr-protocol/nips/blob/master/65.md) | Relay list metadata (kind 10002) | Full, and central. Read for every author routed to. Your own is edited and published on its own screen, reached from your profile — read and write markers per relay, replacing the previous list. |
| [A3](https://github.com/nostr-protocol/nips/blob/master/A3.md) | Payment targets (kind 10133) | Full. `payto` tags read for any type, including ones this app has never heard of, and shown on the profile beside the lightning address. Your own list is edited there and published as its own replaceable event. |

Planned: **NIP-46** (remote `bunker://` signing). Quartz already provides the client, and it would
be a second `EventSigner` — but a bunker URI carries relay URLs, so those relays have to go through
the approval gate like any other.

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

Beyond Quartz the app adds Compose, androidx activity/lifecycle/core-ktx, OkHttp, and — for the QR
scanner alone — CameraX and `com.google.zxing:core`, a pure-Java decoder with no transitive
dependencies of its own. There is no navigation library, image loader, DI framework, markdown
library, or JSON library in `core`. The last three are written here instead, each because the whole
need is small and the alternative brings its own network, its own cache or its own parser: the
image loader is one page with one HTTP call in it, and the markdown reader handles what a NIP-23
article uses and leaves anything else as the author's own text.

The account key is encrypted with an `AndroidKeyStore` AES-GCM key in about sixty lines of standard
JCA rather than depending on the deprecated `androidx.security:security-crypto`, and showing it
again goes through `KeyguardManager` rather than pulling in `androidx.biometric` for one call.

The app requests two permissions. `INTERNET`, and `CAMERA` for the QR scanner — asked for when that
screen opens, declared `required="false"` so a device without a camera still installs, and used by
one non-exported activity that decodes frames in memory and stores nothing.

## Building

```sh
./gradlew :app:assembleDebug
./gradlew :core:jvmTest :nostr-quartz:testDebugUnitTest :app:testDebugUnitTest
```

The unit tests are named individually because `core` is a multiplatform module and so has no plain
`test` task — `./gradlew test` silently skips it.

Requires JDK 17. Only `nostr-quartz` and `app` need the Android SDK (API 36); `core` applies no
Android plugin and builds on the JDK alone. `google()` is needed as a repository even for
`nostr-quartz`, because Quartz depends on androidx artifacts published only there.

## Status

`core`, `nostr-quartz`, the view models and the Compose UI are compile-verified, with 211 unit tests
covering the relay gate, the set-cover router, NIP-11 consent, NIP-23 addressable replacement, the
NIP-55 wire format, `nprofile` relay hints, NIP-65 publishing and its separation from the local
permission list, the onboarding sequence — including that a first launch
queries nothing, that the key screen is unreachable from the tab bar, and that logging in asks
before touching the app's own relays — and round-trips through real secp256k1 signing and real
Quartz parsing.

`core` builds and tests under Gradle itself — `:core:jvmTest` runs its 167 tests green — which is
possible precisely because it no longer applies the Android plugin. The rest of the Android build
has not been run: `dl.google.com` was unreachable from the machine this was written on, so no
androidx artifact or Android SDK could be fetched. The modules were verified by
compiling against `quartz-jvm` and Compose Multiplatform, which mirror the same APIs, on a plain
JVM. Eight files touching Android-only APIs are therefore unverified — `MainActivity`,
`WayfarerApplication`, `AndroidStores`, `Nip55Bridge`, `AppSignerFactory`, `DeviceAuthBridge`,
`SecureScreen` and `QrScan` (the CameraX viewfinder in particular) — as are the AGP, `compileSdk`,
Compose BOM and CameraX versions in `gradle/libs.versions.toml`.

## License

MIT.
