package app.wayfarer.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.wayfarer.android.platform.Nip55Bridge
import app.wayfarer.android.signer.Nip55Protocol
import app.wayfarer.android.ui.LoadingScreen
import app.wayfarer.android.ui.WayfarerApp
import app.wayfarer.android.ui.theme.WayfarerTheme
import app.wayfarer.android.viewmodel.ExternalSignerIdentity
import app.wayfarer.core.Wayfarer
import app.wayfarer.core.model.PubKey

class MainActivity : ComponentActivity() {
    private lateinit var nip55Bridge: Nip55Bridge

    /** Queried once: this is a PackageManager lookup, not something to do per frame. */
    private var signerInstalled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val wayfarerApp = application as WayfarerApplication

        // Must be registered before the activity reaches STARTED, so it cannot be
        // moved into a composable or behind a condition.
        nip55Bridge =
            Nip55Bridge(
                registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    nip55Bridge.onActivityResult(result)
                },
            )
        wayfarerApp.nip55Bridge = nip55Bridge
        signerInstalled = Nip55Bridge.isSignerInstalled(this)

        setContent {
            WayfarerTheme {
                val wayfarer by produceState<Wayfarer?>(initialValue = null) { value = wayfarerApp.wayfarer() }

                when (val ready = wayfarer) {
                    null -> LoadingScreen("Starting up…")
                    else ->
                        WayfarerApp(
                            core = ready,
                            scope = wayfarerApp.scope,
                            externalSignerLogin = signerLoginFor(ready),
                        )
                }
            }
        }
    }

    /**
     * Explicitly typed rather than inlined at the call site: this file is the one
     * part of the UI wiring no compiler in this project's offline harness can
     * check, so its types are spelled out instead of inferred.
     */
    private fun signerLoginFor(core: Wayfarer): (suspend () -> ExternalSignerIdentity?)? =
        if (!signerInstalled) null else ({ askSignerForIdentity(core) })

    /**
     * NIP-55 `get_public_key`: the signer returns both the user's pubkey and its
     * own package name, and every later request is addressed to that package.
     */
    private suspend fun askSignerForIdentity(core: Wayfarer): ExternalSignerIdentity? {
        val reply = nip55Bridge.send(Nip55Protocol.getPublicKey())
        val ok = reply as? Nip55Protocol.Reply.Ok ?: return null
        val raw = ok.result ?: return null
        // Signers return an npub or bare hex depending on the app.
        val pubKey = core.bech32.decodePubKey(raw) ?: PubKey.parseOrNull(raw) ?: return null
        val packageName = ok.packageName ?: return null
        return ExternalSignerIdentity(pubKey, packageName)
    }

    override fun onDestroy() {
        // The bridge holds this activity's launcher; leaving it on the
        // application would hand a destroyed activity to the next signature.
        if ((application as WayfarerApplication).nip55Bridge === nip55Bridge) {
            (application as WayfarerApplication).nip55Bridge = null
        }
        super.onDestroy()
    }
}
