/*
 * Copyright (C) 2026 contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package com.movtery.zalithlauncher.ui.screens.content.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.game.path.GamePathManager
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.screens.NestedNavKey
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.ui.screens.TitledNavKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties

private data class VoiceChatInstall(
    val name: String,
    val modFile: File,
    val configFile: File,
    val denoiserEnabled: Boolean
)

private fun findVoiceChatInstalls(gamePath: String): List<VoiceChatInstall> {
    val root = File(gamePath)
    val modDirectories = linkedMapOf<String, File>()
    File(root, "mods").takeIf { it.isDirectory }?.let { modDirectories["Default game directory"] = it }
    File(root, "versions").listFiles()?.filter { it.isDirectory }?.forEach { versionDir ->
        File(versionDir, "mods").takeIf { it.isDirectory }?.let {
            modDirectories[versionDir.name] = it
        }
    }
    return modDirectories.flatMap { (instanceName, modsDir) ->
        modsDir.listFiles()?.filter {
            it.isFile && it.extension.equals("jar", true) &&
                (it.name.contains("voicechat", true) || it.name.contains("simple-voice-chat", true))
        }.orEmpty().map { mod ->
            val config = File(modsDir.parentFile, "config/voicechat/voicechat-client.properties")
            val properties = Properties()
            if (config.isFile) runCatching { config.inputStream().use(properties::load) }
            VoiceChatInstall(
                name = instanceName,
                modFile = mod,
                configFile = config,
                denoiserEnabled = properties.getProperty("denoiser", "false").equals("true", true)
            )
        }
    }.distinctBy { it.modFile.absolutePath }
}

private fun setVoiceChatDenoiser(installs: List<VoiceChatInstall>, enabled: Boolean) {
    installs.forEach { install ->
        val properties = Properties()
        if (install.configFile.isFile) {
            install.configFile.inputStream().use(properties::load)
        }
        properties.setProperty("denoiser", enabled.toString())
        install.configFile.parentFile?.mkdirs()
        install.configFile.outputStream().use { output ->
            properties.store(output, "Voice Enhancer managed settings")
        }
    }
}

@Composable
fun VoiceEnhancerScreen(
    key: NestedNavKey.Settings,
    settingsScreenKey: TitledNavKey?,
    mainScreenKey: TitledNavKey?
) {
    val gamePath by GamePathManager.currentPath.collectAsState()
    val scope = rememberCoroutineScope()
    var installs by remember { mutableStateOf<List<VoiceChatInstall>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val enabled = installs.isNotEmpty() && installs.all { it.denoiserEnabled }

    LaunchedEffect(gamePath) {
        isLoading = true
        installs = withContext(Dispatchers.IO) { findVoiceChatInstalls(gamePath) }
        isLoading = false
    }

    BaseScreen(
        Triple(key, mainScreenKey, false),
        Triple(NormalNavKey.Settings.VoiceEnhancer, settingsScreenKey, false)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Voice Enhancer", style = MaterialTheme.typography.headlineSmall)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Microphone noise reduction", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (enabled) "RNNoise denoiser is enabled in every detected installation."
                                else "Enable the supported denoiser for detected Simple Voice Chat installations.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enabled,
                            enabled = installs.isNotEmpty() && !isLoading && !isSaving,
                            onCheckedChange = { checked ->
                                isSaving = true
                                error = null
                                scope.launch {
                                    val result = runCatching {
                                        withContext(Dispatchers.IO) { setVoiceChatDenoiser(installs, checked) }
                                    }
                                    result.onSuccess {
                                        installs = withContext(Dispatchers.IO) { findVoiceChatInstalls(gamePath) }
                                    }.onFailure {
                                        error = it.message ?: "Could not update the voice chat configuration."
                                    }
                                    isSaving = false
                                }
                            }
                        )
                    }
                }
            }

            Text("Detected voice chat mods", style = MaterialTheme.typography.titleMedium)
            when {
                isLoading -> Text("Scanning the selected Minecraft directory…")
                installs.isEmpty() -> Text(
                    "No Simple Voice Chat installation was found in the selected game directory. Install the mod and reopen this page.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> installs.forEach { install ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Simple Voice Chat", style = MaterialTheme.typography.titleSmall)
                            Text(install.modFile.name, style = MaterialTheme.typography.bodySmall)
                            Text(
                                install.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (install.denoiserEnabled) "Denoiser configured · restart the game if it is running"
                                else "Detected · denoiser currently disabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Text(
                "The switch updates Simple Voice Chat's denoiser setting in detected game instances. It does not inject an audio processor into the running game; restart Minecraft for configuration changes to take effect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
