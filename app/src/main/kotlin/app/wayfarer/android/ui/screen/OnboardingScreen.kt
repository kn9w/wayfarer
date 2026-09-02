package app.wayfarer.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wayfarer.android.platform.SecureScreen
import app.wayfarer.android.ui.MessageBanner
import app.wayfarer.android.viewmodel.AppController
import app.wayfarer.android.viewmodel.Introduction
import app.wayfarer.android.viewmodel.OnboardingStep
import app.wayfarer.android.viewmodel.RelayPurpose

/**
 * Onboarding: the whole window, from launch to the first thing worth looking at.
 *
 * It is one surface rather than a set of tab destinations, and that is the point.
 * The key backup step lives in here, and when it was drawn inside the app's
 * scaffold a single tap on the navigation bar left the screen for good — with
 * the only copy of the key on it.
 */
@Composable
fun OnboardingSurface(
    controller: AppController,
    step: OnboardingStep,
) {
    val busy by controller.busy.collectAsStateWithLifecycle()
    val message by controller.message.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { MessageBanner(it, controller::dismissMessage) }

        when (step) {
            OnboardingStep.Start -> StartScreen(controller, busy)
            is OnboardingStep.Learn -> IntroductionScreen(controller, step.page)
            OnboardingStep.AccountChoice -> AccountChoiceScreen(controller, busy)
            is OnboardingStep.Backup -> BackupScreen(step.nsec, controller::finishBackup)
            OnboardingStep.EntryPoint -> EntryPointScreen(controller, busy)
            is OnboardingStep.ApproveRelays -> ApproveRelaysScreen(controller, step, busy)
        }
    }
}

/** Vertical page frame shared by every onboarding step. */
@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        content()
    }
}

@Composable
private fun StartScreen(
    controller: AppController,
    busy: Boolean,
) {
    var key by remember { mutableStateOf("") }

    Page {
        Text("Wayfarer", style = MaterialTheme.typography.headlineMedium)
        Text(
            "A nostr app that talks to no server until you say which ones.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Button(onClick = controller::beginIntroduction, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("New to nostr?")
        }
        Text(
            "Two short screens on what an account and a relay actually are. You can make an account at the " +
                "end, or skip it and look around first.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        Text("Already have an account?", style = MaterialTheme.typography.titleSmall)

        if (controller.externalSignerAvailable) {
            OutlinedButton(
                onClick = controller::loginWithExternalSigner,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Use a signer app")
            }
            Text(
                "Your key stays in that app (NIP-55). Wayfarer never sees it, and you approve every signature there.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("npub or nsec") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { controller.login(key) },
            enabled = !busy && key.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Log in")
        }
        Text(
            "An nsec is the secret half: it can read and post. An npub is the public half — everything is " +
                "readable, nothing can be posted.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (controller.canLeaveOnboarding) {
            TextButton(onClick = controller::leaveOnboarding, modifier = Modifier.fillMaxWidth()) {
                Text("Back to the app")
            }
        }
    }
}

/**
 * The introduction. One idea per page, in the words a person would use before
 * they have any of the vocabulary.
 */
@Composable
private fun IntroductionScreen(
    controller: AppController,
    pageIndex: Int,
) {
    val page = Introduction.pages[pageIndex]

    Page {
        Text(
            "${pageIndex + 1} of ${Introduction.pages.size}",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(page.title, style = MaterialTheme.typography.headlineSmall)
        for (paragraph in page.body) {
            Text(paragraph, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = controller::introductionBack, modifier = Modifier.weight(1f)) {
                Text("Back")
            }
            Button(onClick = controller::introductionNext, modifier = Modifier.weight(1f)) {
                Text(if (pageIndex == Introduction.pages.lastIndex) "Done" else "Next")
            }
        }
    }
}

/**
 * Two ways forward, given the same weight on purpose.
 *
 * Reading nostr needs no key at all, so pushing a stranger into generating one
 * they do not yet understand — and then having to keep it safe forever — is the
 * wrong default. Both buttons lead somewhere real.
 */
@Composable
private fun AccountChoiceScreen(
    controller: AppController,
    busy: Boolean,
) {
    Page {
        Text("Do you want an account?", style = MaterialTheme.typography.headlineSmall)
        Text(
            "An account is a key this phone generates. You need one to post, to follow people, or to have a " +
                "profile. You do not need one to read anything.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Button(onClick = controller::createAccount, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Create an account")
        }
        Text(
            "Made on this device, in a moment. You will be shown the key straight away, and it stays " +
                "available in Settings.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedButton(onClick = controller::continueWithoutAccount, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Not for now")
        }
        Text(
            "Look around first. Everything except posting works, and you can make an account whenever you " +
                "like — nothing you do now is lost.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The key, shown once, on a screen with nothing else on it.
 *
 * Nothing here writes to the clipboard: the field is selectable, so copying is
 * the user's own deliberate act and no other app is handed the key by accident.
 */
@Composable
fun BackupScreen(
    nsec: String,
    onContinue: () -> Unit,
) {
    // A freshly generated key is on screen here, so nothing may capture it.
    SecureScreen()

    var acknowledged by remember { mutableStateOf(false) }

    Page {
        Text("This is your account", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Anyone holding this string is you. Nobody can reset it, and nobody — this app included — can " +
                "recover it if it is lost.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = nsec,
            onValueChange = {},
            readOnly = true,
            label = { Text("nsec") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Save it in a password manager, or write it down. It is encrypted on this phone by Android's " +
                "keystore, and you can see it again at any time in Settings — but if you lose the phone " +
                "without a copy, the account is gone.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
            Text(
                "I have saved it, or I understand I can find it in Settings.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(onClick = onContinue, enabled = acknowledged, modifier = Modifier.fillMaxWidth()) {
            Text("Continue")
        }
    }
}

/**
 * Where to start looking.
 *
 * The honest question at the end of setup: this app knows nowhere to look and
 * nobody to look for, and it would rather ask than quietly pick for you.
 */
@Composable
private fun EntryPointScreen(
    controller: AppController,
    busy: Boolean,
) {
    var input by remember { mutableStateOf("") }

    Page {
        Text("Where should we start?", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Wayfarer has not contacted anything yet. Give it one place to begin — a relay you have heard of, " +
                "or somebody you want to read.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Relay address, npub or nprofile") },
            placeholder = { Text("wss://relay.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { controller.submitEntryPoint(input) },
                enabled = !busy && input.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text("Continue") }
            if (controller.qrScanAvailable) {
                OutlinedButton(
                    onClick = controller::scanEntryPoint,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Scan a QR code") }
            }
        }
        Text(
            "A relay address starts with wss:// and is a server that keeps posts. An npub names a person; an " +
                "nprofile names a person and says which relays to find them on.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()

        OutlinedButton(onClick = controller::browseSuggestedRelays, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("I don't know any — show me yours")
        }
        Text(
            "Wayfarer ships with a handful of well-known relays. It will show you which ones before it " +
                "contacts any of them.",
            style = MaterialTheme.typography.bodySmall,
        )

        TextButton(onClick = controller::skipEntryPoint, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Skip — I'll choose later")
        }
    }
}

/**
 * The consent screen for every relay this app is about to talk to.
 *
 * Two cases, and the difference matters enough to say out loud: relays named by
 * the thing the user pasted, and relays this app picked because it has nowhere
 * else to look. Either way there is a field to name a relay instead, because
 * "these are your only options" would not be true.
 */
@Composable
private fun ApproveRelaysScreen(
    controller: AppController,
    step: OnboardingStep.ApproveRelays,
    busy: Boolean,
) {
    var ownRelay by remember { mutableStateOf("") }

    Page {
        Text(
            if (step.areAppDefaults) "Wayfarer needs somewhere to ask" else "This link names its own relays",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(purposeExplanation(step), style = MaterialTheme.typography.bodyMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (relay in step.relays) {
                    Text(relay.display(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Text(
            "Approving these lets Wayfarer read from them. They will see your IP address, as any website you " +
                "visit would. Nothing is posted, and you can withdraw any of this later in Relays.",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(onClick = controller::approveProposedRelays, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (step.relays.size == 1) "Use this relay" else "Use these relays")
        }

        HorizontalDivider()

        Text("Or name a relay you trust instead", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = ownRelay,
            onValueChange = { ownRelay = it },
            label = { Text("wss://relay.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { controller.useRelayInstead(ownRelay) },
            enabled = !busy && ownRelay.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Use this one instead") }

        TextButton(onClick = controller::skipEntryPoint, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Not now — connect to nothing")
        }
    }
}

private fun purposeExplanation(step: OnboardingStep.ApproveRelays): String =
    when (val purpose = step.purpose) {
        is RelayPurpose.FindPerson ->
            if (step.areAppDefaults) {
                "An npub says who somebody is, but not where they post. To find ${purpose.npub.take(12)}… " +
                    "Wayfarer would have to ask the relays it ships with, listed below — they are its guess, " +
                    "not that person's own."
            } else {
                "The nprofile you gave says this person can be found on the relays below. They are that " +
                    "link's claim, so Wayfarer is asking before it believes it."
            }
        is RelayPurpose.FindAccount ->
            "To load your profile, who you follow and which relays you use, Wayfarer has to ask somewhere. " +
                "Nothing is approved yet, so all it has are the relays it ships with, listed below. If you " +
                "know which relays you use, name one instead — it will find the rest from there."
        RelayPurpose.Browse ->
            "These are the relays Wayfarer ships with. They are a starting point, not a recommendation, and " +
                "nothing has been contacted yet."
    }
