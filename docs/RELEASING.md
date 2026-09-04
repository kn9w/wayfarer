# Releasing Wayfarer

How a build gets from this branch onto somebody's phone.

Two gates come before any of it, and neither is a formality. Everything after
them is mechanical.

---

## Gate 1 — CI has to go green once

**The Android modules have never been compiled.** `core` builds and tests on its
own and always has; `app` and `nostr-quartz` have not, because the machine this
project was written on could not reach `dl.google.com` and so could not fetch the
Android Gradle Plugin, the Android SDK, or any androidx artifact. 146 tests in
those two modules — including `GatedWebsocketBuilderTest`, which covers the relay
socket gate — have never been executed.

So a CI run here is not a regression check. It is the first time a compiler has
looked at that code, and **each run should be expected to find something.** Work
through them one at a time; this is a first compile, not a broken build.

**Fixed so far.** The first run failed eight AAR metadata checks. Every recent
Quartz release publishes `minCompileSdk=37` in its own metadata — there is no
version of it that builds against 36 — and androidx.core and Compose, which
Quartz pulls up transitively, additionally require AGP 9.1 or newer. So
`compileSdk` is 37, AGP is 9.4.0, and the wrapper is Gradle 9.7.1, which AGP 9
requires. `targetSdk` deliberately stayed at 36: compileSdk only makes newer APIs
available, while targetSdk opts the app into new runtime behaviour, and that one
moves on its own with a device in hand.

**Everything compiles.** `:app:compileDebugKotlin`, `:nostr-quartz:compileDebugKotlin`
and both test source sets now build. Of the eight Android-only files that no
offline harness could check, exactly one had a defect — a `Modifier.size` used
once and never imported. `GatedWebsocketBuilderTest` has run for the first time
and its seven tests pass, so the relay socket gate is now covered by a suite that
has actually executed rather than only compiled.

Still unverified, and the likely next things to report:

- The remaining androidx and CameraX versions in `gradle/libs.versions.toml`,
  which have never been resolved against Google's Maven repository.
- The eight Android-only files that touch platform APIs no offline harness could
  check: `MainActivity`, `WayfarerApplication`, `AndroidStores`, `Nip55Bridge`,
  `AppSignerFactory`, `DeviceAuthBridge`, `SecureScreen`, `QrScan`.
- Quartz API drift — the `nostr-quartz` module names Quartz types directly, and
  those names were written against documentation rather than a compiler.
- AGP 9 removals and DSL moves. Three are already done:
  `android.nonTransitiveRClass` is gone from `gradle.properties` (the default
  since AGP 8); the `org.jetbrains.kotlin.android` plugin is gone from both
  Android modules, because AGP 9 compiles Kotlin itself and applying the
  separate plugin is a hard error; and the `kotlin { jvmToolchain(17) }` blocks
  went with it, since `jvmTarget` now follows
  `android.compileOptions.targetCompatibility`. Still standing:
  `android.useAndroidX`, and `android.sourceSets…kotlin.srcDir` in
  `app/build.gradle.kts`, which the migration guide neither blesses nor
  forbids — if it breaks, the documented form is `kotlin.directories +=`.
  A fourth surfaced on its own: `kotlin("test")` needs naming in full as
  `org.jetbrains.kotlin:kotlin-test-junit` in the AGP-compiled modules, since
  that helper leans on the Kotlin plugin for both its version and its framework
  variant.
- Kotlin and AGP version skew. Kotlin 2.4.10's published compatibility table
  lists AGP 8.5.2–9.1.0, and this build is on AGP 9.4.0. With built-in Kotlin
  the Android modules no longer use KGP at all, so the overlap that matters is
  narrower than that table suggests — but `core` is still compiled by KGP
  2.4.10 and consumed by modules AGP compiles, and the Compose compiler plugin
  is still pinned to 2.4.10. A Kotlin metadata or Compose-plugin version
  complaint would be this.

Push the branch and let CI run. Fix what it reports, push again, repeat until
green. Nothing below is meaningful until it is.

### Setting up the runner (Gitea)

GitHub Actions needs nothing — the workflow runs on hosted runners as it stands.
Gitea needs a runner registered once:

```sh
# On the machine that will build. Get a registration token from
# Gitea → Site Administration → Actions → Runners → Create new runner.
docker run -d --restart always --name gitea-runner \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v $PWD/runner-data:/data \
  -e GITEA_INSTANCE_URL=https://your.gitea.host \
  -e GITEA_RUNNER_REGISTRATION_TOKEN=<token> \
  -e GITEA_RUNNER_NAME=wayfarer-builder \
  gitea/act_runner:latest
```

Then enable Actions on the repository: **Settings → Repository → Advanced →
Enable Repository Actions**.

Two things about Gitea specifically:

- **The workflow file is already portable.** Gitea reads GitHub Actions syntax
  and looks in `.gitea/workflows` first, falling back to `.github/workflows`.
  There is nothing to duplicate — the same `ci.yml` runs on both forges.
- **Actions are fetched from github.com by default.** If your Gitea host cannot
  reach it, set a mirror in the runner's config, or vendor the four actions this
  repo uses (`checkout`, `setup-java`, `setup-android`, `cache`,
  `upload-artifact`) into a local org.

The default `act_runner` image carries no Android SDK. `android-actions/setup-android`
installs it, which is why the workflow calls it unconditionally rather than only
on Gitea.

---

## Gate 2 — smoke-test a release build on a real device

`isMinifyEnabled = true` is new. **R8 changes what runs**: it renames and deletes
code, and the dependency graph here uses reflection in three places that R8
cannot see through — Jackson reads field names off Quartz's event classes, JNI
looks up secp256k1 methods by name from C, and kotlinx-serialization finds
generated serializers through a companion. `app/proguard-rules.pro` keeps all
three, but a keep rule is a claim until something exercises it.

`assembleRelease` succeeding proves only that shrinking completed. It does not
prove the app works.

```sh
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Walk the whole consent path, because that is what R8 would break silently:

- [ ] First launch asks *Where should we start?* and contacts nothing before you answer
- [ ] Approve a relay — the feed loads, and the connection dot lights
- [ ] **Block that relay — the connection count drops** (this is the new disconnect path)
- [ ] Fetch a relay's NIP-11 info; try one that is slow or dead and confirm it fails rather than spinning forever
- [ ] Create an account, see the nsec once, find it again in Settings behind the lock screen
- [ ] **Turn off the screen lock and try again** — it should show the key and say why it could not ask
- [ ] Allow a picture server; an avatar draws
- [ ] Open a picture full-screen; pinch-zoom works
- [ ] **Play a video** — it downloads first, then plays (this path changed completely)
- [ ] Publish a note and read the per-relay report
- [ ] Sign in with an external signer, if you have one installed
- [ ] Log out — confirm relays, follows and pictures are gone

A crash on any of these is almost certainly a missing keep rule. Get the mapping
file (`app/build/outputs/mapping/release/mapping.txt`) and run the stack trace
through `retrace` before guessing.

---

## Deploying

### 1. Create the upload keystore — once, ever

```sh
keytool -genkey -v \
  -keystore upload.jks \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias wayfarer
```

**This file cannot be regenerated.** Losing it means never being able to update
the app under the same Play listing again. Back it up somewhere that is not the
forge and not this repository — a password manager, an encrypted volume, offline
media. `.gitignore` does not cover `*.jks`; do not put it in the working tree.

### 2. Add the signing secrets

On either forge: **Settings → Secrets**.

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 upload.jks` |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `wayfarer` |
| `KEY_PASSWORD` | the key password |

`release.yml` decodes the keystore into the runner's temp directory, builds, and
deletes it in an `always()` step. Without the secrets it still builds — unsigned
— so a fork can tag without failing.

### 3. Set the version

`app/build.gradle.kts`:

```kotlin
versionCode = 1        // must increase on every upload. Never reuse one.
versionName = "0.1.0"
```

`versionCode` is what the store orders builds by; `versionName` is what people
read. Commit the bump.

### 4. Tag

```sh
git tag -a v0.1.0 -m "Wayfarer 0.1.0"
git push origin v0.1.0
```

`release.yml` fires on `v*`: it runs the tests again (a tag is not a reason to
skip them), builds `:app:bundleRelease` and `:app:assembleRelease` signed, and
uploads the `.aab`, the `.apk` and `mapping.txt` as artifacts.

### 5. Distribute

**Google Play** — upload the `.aab` to the Play Console, internal testing track
first. Upload `mapping.txt` alongside it or every crash report is unreadable.
Two things about this app will draw questions in review:

- *Data safety form:* the honest answer is that no data is collected. There is no
  analytics, no crash reporting, no ad SDK and no backend — the app talks only to
  relays and picture servers the user has individually approved. Say that.
- *Permissions:* `CAMERA` is declared and Play will ask what for. It is the QR
  scanner, used by one non-exported activity, which decodes frames in memory and
  stores nothing.

**F-Droid** — a better fit for this app than Play, and worth the effort: the
whole design argues for a client whose build is reproducible from source.
Requires an [RFP issue](https://gitlab.com/fdroid/rfp) and a build recipe. Note
that F-Droid builds from source itself, so the signing key above is not used;
they sign with theirs.

**Direct APK** — attach the `.apk` from the release artifacts to a Gitea/GitHub
release. Pair it with [Obtainium](https://github.com/ImranR98/Obtainium) so
people get updates without a store. This is the lowest-friction route and needs
no review.

### 6. After

- Keep `mapping.txt` for every shipped `versionCode`. Without it a crash report
  from that build is noise.
- Tag the commit that actually shipped, not the branch.

---

## Known gaps at 0.1.0

None of these block a release; all were found in the audit and are worth
knowing before you ship rather than after.

| | |
|---|---|
| `minSdk = 26` | Reaches Android 8.0 devices, well past their last security update, and forces `BitmapFactory` over the safer `ImageDecoder` for attacker-supplied images. Raising to 28 costs little while the install base is zero. |
| No dependency verification | `./gradlew --write-verification-metadata sha256 help` generates `gradle/verification-metadata.xml`. Do it once the build is stable, not before. |
| NIP-55 reply bundle | `Nip55Bridge.onActivityResult` enumerates every extra a signer app returns. Reading the five known keys by name would avoid unparceling untrusted types. |
| Private follow list at rest | Stored in plaintext `SharedPreferences` while the nsec is keystore-encrypted. `SecretStore` already has the plumbing. |
| No crash reporting | Deliberate, and consistent with the privacy posture — but it means no visibility into production failures. Decide knowingly. |
