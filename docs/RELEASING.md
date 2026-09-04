# Releasing Wayfarer

How a build gets from this repository onto somebody's phone.

Assumes Linux and a JDK 17 or newer on `PATH`. CI is green: all 374 tests run on
every push, across `core`, `nostr-quartz` and `app`, and both a debug and a
shrunk release APK are assembled. So the build is no longer the question. One
gate remains before anything is handed to another person, and it is not a
formality.

---

## The gate — smoke-test a release build on a real device

`isMinifyEnabled = true`. **R8 changes what runs**: it renames and deletes code,
and this dependency graph uses reflection in three places R8 cannot see through
— Jackson reads field names off Quartz's event classes, JNI looks up secp256k1
methods by name from C, and kotlinx-serialization finds generated serializers
through a companion. `app/proguard-rules.pro` keeps all three, but **a keep rule
is a claim until something exercises it**, and CI cannot exercise it: assembling
the release APK proves only that shrinking completed, not that the result runs.

This is the single largest remaining risk in shipping, and twenty minutes with a
phone closes it.

```sh
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Walk the whole consent path, because that is what R8 would break silently:

- [ ] First launch asks *Where should we start?* and contacts nothing before you answer
- [ ] Approve a relay — the feed loads, and the connection dot lights
- [ ] **Block that relay — the connection count drops** (the disconnect path)
- [ ] Fetch a relay's NIP-11 info; try a dead one and confirm it fails rather than spinning forever
- [ ] Create an account, see the nsec once, find it again in Settings behind the lock screen
- [ ] **Turn off the screen lock and try again** — it shows the key and says why it could not ask
- [ ] Allow a picture server; an avatar draws
- [ ] Open a picture full-screen; pinch-zoom works
- [ ] **Play a video** — it downloads first, then plays
- [ ] Publish a note and read the per-relay report
- [ ] Sign in with an external signer, if you have one installed
- [ ] Log out — confirm relays, follows and pictures are gone

A crash on any of these is almost certainly a missing keep rule. Take
`app/build/outputs/mapping/release/mapping.txt` and run the stack trace through
`retrace` before guessing at a fix.

---

## 1. Create the upload keystore — once, ever

```sh
keytool -genkeypair -v \
  -keystore ~/wayfarer-upload.jks \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias wayfarer
```

It asks for two passwords (keystore and key — they may be the same) and some
identifying details, none of which anyone checks.

**This file cannot be regenerated.** Losing it means never being able to update
the app under the same identity again — not on Play, and not for anyone who
sideloaded it, because Android refuses an update signed by a different key.
Back it up somewhere that is neither this repository nor the forge: a password
manager, an encrypted volume, a USB stick in a drawer. The path above keeps it
outside the working tree on purpose; `.gitignore` does not cover `*.jks`.

**The same key signs every build anyone installs**, pre-release and release
alike. That is what lets somebody who installed an early build update to a later
one in place, rather than uninstalling — which for an account whose key lives on
that phone means destroying the only copy of it.

## 2. Add the signing secrets

```sh
base64 -w0 ~/wayfarer-upload.jks > ~/wayfarer-upload.b64
```

Paste its contents into the forge under **Settings → Secrets**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the contents of `wayfarer-upload.b64` |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `wayfarer` |
| `KEY_PASSWORD` | the key password |

`release.yml` decodes the keystore into the runner's temp directory, builds, and
deletes it in an `always()` step. Without these secrets it still builds — and
says in the job summary that the result is **unsigned and cannot be installed**
— so a fork can tag without failing.

### Building a signed release locally

The same environment variables the workflow sets. They are read at configuration
time, so export them before invoking Gradle:

```sh
export WAYFARER_KEYSTORE=~/wayfarer-upload.jks
export WAYFARER_KEYSTORE_PASSWORD=…
export WAYFARER_KEY_ALIAS=wayfarer
export WAYFARER_KEY_PASSWORD=…
./gradlew :app:assembleRelease :app:bundleRelease
```

Unset, the build produces an unsigned APK rather than failing.

---

## 3. Version, tag, release

Two fields in `app/build.gradle.kts`, and they mean different things:

```kotlin
versionCode = 1              // stores order builds by it; never reused
versionName = "0.1.0-beta.1" // what people read
```

**Every build uploaded anywhere needs a new `versionCode`.** `versionName` is
free text; a `-alpha`, `-beta` or `-rc` suffix marks a build as one to try
rather than one to rely on, and the release workflow reads the same suffixes
from the tag.

Bump both, commit, then tag:

```sh
git tag -a v0.1.0-beta.1 -m "Wayfarer 0.1.0-beta.1"
git push origin v0.1.0-beta.1
```

`release.yml` fires on any `v*` tag. It runs the full test suite first — a tag
is not a reason to skip them — then builds and signs, uploads the `.aab`, the
`.apk` and `mapping.txt` as artifacts, and publishes a release page with the APK
attached. A tag containing `-alpha`, `-beta` or `-rc` is marked a **pre-release**,
which keeps it off "latest" and out of the way of anyone who came looking for
the finished thing.

The release page is published **only when the build was signed**, because a
release page carrying an APK nobody can install is worse than none. If the
secrets are not set the artifacts still exist and the job summary says why there
is no release.

---

## 4. Distribution

**Direct APK and [Obtainium](https://github.com/ImranR98/Obtainium)** — the
release page has a public link, and Obtainium pointed at the repository gives
people automatic updates, including from Gitea. For this app's audience this is
arguably the destination rather than a stepping stone. Testers get the
unknown-sources prompt once.

**F-Droid** — a better fit than Play, and worth the effort: the whole design
argues for a client whose build is reproducible from source. Needs an
[RFP issue](https://gitlab.com/fdroid/rfp) and a build recipe. F-Droid builds
from source and signs with its own key, so the upload keystore is not used —
which also means an F-Droid install and a direct-APK install cannot replace one
another. Pick one to promote.

**Google Play** — upload the `.aab` to the Play Console, internal testing track
first. Upload `mapping.txt` alongside it or every crash report is unreadable.
Two things will draw questions in review:

- *Data safety form.* The honest answer is that no data is collected. There is
  no analytics, no crash reporting, no ad SDK and no backend — the app talks
  only to relays and picture servers the user has individually approved. Say
  exactly that.
- *Permissions.* `CAMERA` is declared and Play will ask what for. It is the QR
  scanner, used by one non-exported activity that decodes frames in memory and
  stores nothing.

Play also requires a privacy policy URL even for an app that collects nothing.

### After

- Keep `mapping.txt` for every shipped `versionCode`. Without it a crash report
  from that build is noise.
- Tag the commit that actually shipped.

---

## Notes on the pipeline

**Gitea.** The workflow file is already portable: Gitea reads GitHub Actions
syntax and looks in `.gitea/workflows` first, falling back to
`.github/workflows`, so the same `ci.yml` runs on both forges with nothing
duplicated. Register a runner once:

```sh
docker run -d --restart always --name gitea-runner \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v $PWD/runner-data:/data \
  -e GITEA_INSTANCE_URL=https://your.gitea.host \
  -e GITEA_RUNNER_REGISTRATION_TOKEN=<token> \
  -e GITEA_RUNNER_NAME=wayfarer-builder \
  gitea/act_runner:latest
```

Then **Settings → Repository → Advanced → Enable Repository Actions**.

Two Gitea specifics. Actions are fetched from github.com by default, so if your
host cannot reach it, set a mirror in the runner config or vendor the five this
repo uses (`checkout`, `setup-java`, `setup-android`, `cache`,
`upload-artifact`). And the release-publishing step is the one place the forges
differ: Gitea's releases API is largely GitHub-compatible and
`softprops/action-gh-release` usually works there unchanged, but the step is
marked `continue-on-error` so that where it does not, the artifacts are still
built and the fix is swapping in a Gitea-specific action rather than a failed
release.

**Toolchain.** compileSdk 37, AGP 9.4.0, Gradle 9.7.1 (checksum-pinned), JDK 17
toolchain running on JDK 21. `targetSdk` is deliberately still 36 — it opts the
app into new runtime behaviour and should move on its own, with a device, rather
than as a side effect of a dependency bump. Worth doing early.

## Known gaps

None block a release; all are worth knowing before shipping rather than after.

| | |
|---|---|
| `minSdk = 26` | Reaches Android 8.0 devices, well past their last security update, and forces `BitmapFactory` over the safer `ImageDecoder` for attacker-supplied images. Raising to 28 costs little while the install base is zero. |
| No dependency verification | `./gradlew --write-verification-metadata sha256 help` generates `gradle/verification-metadata.xml`. Worth doing now the build is stable. |
| NIP-55 reply bundle | `Nip55Bridge.onActivityResult` enumerates every extra a signer app returns. Reading the five known keys by name would avoid unparceling untrusted types. |
| Private follow list at rest | Stored in plaintext `SharedPreferences` while the nsec is keystore-encrypted. `SecretStore` already has the plumbing. |
| Event content parsing | `readProfile` and `readArticle` reach Quartz parsers with content the author chose. A signature proves who wrote an event, not that its content is well-formed — the same reasoning that turned out to be wrong for `fromJsonOrNull`. |
| No crash reporting | Deliberate, and consistent with the privacy posture — but it means no visibility into production failures. Decide knowingly. |
