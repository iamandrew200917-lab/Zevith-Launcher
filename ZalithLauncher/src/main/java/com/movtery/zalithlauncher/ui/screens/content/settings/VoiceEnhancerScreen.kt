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
import java.util.zip.ZipFile

private data class VoiceChatInstall(
    val name: String,
    val modFile: File,
    val configFile: File,
    val enhancerConfigFile: File,
    val denoiserEnabled: Boolean,
    val nativeAudioEnabled: Boolean,
    val enhancerEnabled: Boolean,
    val enhancerInstalled: Boolean
)

private fun hasFabricModId(file: File, modId: String): Boolean = runCatching {
    ZipFile(file).use { zip ->
        val metadata = zip.getEntry("fabric.mod.json")?.let { entry ->
            zip.getInputStream(entry).bufferedReader().use { it.readText() }
        }.orEmpty()
        Regex("\\\"id\\\"\\s*:\\s*\\\"$modId\\\"").containsMatchIn(metadata)
    }
}.getOrDefault(false)

private fun isSimpleVoiceChatMod(file: File): Boolean = runCatching {
    ZipFile(file).use { zip ->
        val fabricMetadata = zip.getEntry("fabric.mod.json")?.let { entry ->
            zip.getInputStream(entry).bufferedReader().use { it.readText() }
        }.orEmpty()
        val forgeMetadata = zip.getEntry("META-INF/mods.toml")?.let { entry ->
            zip.getInputStream(entry).bufferedReader().use { it.readText() }
        }.orEmpty()
        Regex("\\\"id\\\"\\s*:\\s*\\\"voicechat\\\"").containsMatchIn(fabricMetadata) ||
            Regex("""modId\s*=\s*"voicechat"""").containsMatchIn(forgeMetadata)
    }
}.getOrDefault(false)

private fun readProperties(file: File): Properties = Properties().also { properties ->
    if (file.isFile) runCatching { file.inputStream().use(properties::load) }
}

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
            it.isFile && it.extension.equals("jar", true) && isSimpleVoiceChatMod(it)
        }.orEmpty().map { mod ->
            val configDirectory = File(modsDir.parentFile, "config")
            val voiceChatConfig = File(configDirectory, "voicechat/voicechat-client.properties")
            val enhancerConfig = File(configDirectory, "zevith-voice-enhancer.properties")
            val voiceChatProperties = readProperties(voiceChatConfig)
            val enhancerProperties = readProperties(enhancerConfig)
            val enhancerInstalled = modsDir.listFiles()?.any {
                it.isFile && it.extension.equals("jar", true) &&
                    hasFabricModId(it, "zevith_voice_enhancer")
            } == true
            VoiceChatInstall(
                name = instanceName,
                modFile = mod,
                configFile = voiceChatConfig,
                enhancerConfigFile = enhancerConfig,
                denoiserEnabled = voiceChatProperties.getProperty("denoiser", "false").equals("true", true),
                nativeAudioEnabled = voiceChatProperties.getProperty("use_natives", "true").equals("true", true),
                enhancerEnabled = enhancerProperties.getProperty("enabled", "false").equals("true", true),
                enhancerInstalled = enhancerInstalled
            )
        }
    }.distinctBy { it.modFile.absolutePath }
}

private fun setVoiceEnhancerEnabled(installs: List<VoiceChatInstall>, enabled: Boolean) {
    installs.forEach { install ->
        val voiceChatProperties = readProperties(install.configFile)
        voiceChatProperties.setProperty("denoiser", enabled.toString())
        if (enabled) voiceChatProperties.setProperty("use_natives", "true")
        install.configFile.parentFile?.mkdirs()
        install.configFile.outputStream().use { voiceChatProperties.store(it, "Zevith Voice Enhancer settings") }

        val enhancerProperties = readProperties(install.enhancerConfigFile)
        enhancerProperties.setProperty("enabled", enabled.toString())
        install.enhancerConfigFile.parentFile?.mkdirs()
        install.enhancerConfigFile.outputStream().use { enhancerProperties.store(it, "Zevith Voice Enhancer settings") }
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
    val enabled = installs.isNotEmpty() && installs.all {
        it.denoiserEnabled && it.nativeAudioEnabled && it.enhancerEnabled
    }
    val companionReady = installs.isNotEmpty() && installs.all { it.enhancerInstalled }

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
                            Text("Microphone + incoming voice", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    enabled && companionReady -> "Both processing paths are configured."
                                    enabled -> "Microphone noise reduction is configured; install the Zevith companion mod to process incoming voices."
                                    else -> "One switch configures microphone noise reduction and local incoming-voice processing."
                                },
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
                                        withContext(Dispatchers.IO) { setVoiceEnhancerEnabled(installs, checked) }
                                    }
                                    result.onSuccess {
                                        installs = withContext(Dispatchers.IO) { findVoiceChatInstalls(gamePath) }
                                    }.onFailure {
                                        error = it.message ?: "Could not update the voice enhancement settings."
                                    }
                                    isSaving = false
                                }
                            }
                        )
                    }
                }
            }

            Text("Detected voice chat", style = MaterialTheme.typography.titleMedium)
            when {
                isLoading -> Text("Scanning the selected Minecraft directory…")
                installs.isEmpty() -> Text(
                    "Simple Voice Chat was not found in the selected game directory.",
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
                                if (install.enhancerInstalled) "Zevith audio processor: detected"
                                else "Zevith audio processor: not installed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (install.denoiserEnabled && install.nativeAudioEnabled) "Microphone denoiser: configured"
                                else "Microphone denoiser: off",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (install.enhancerEnabled && install.enhancerInstalled) "Incoming voice processing: configured"
                                else "Incoming voice processing: unavailable until the companion mod is installed and enabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Text(
                "The microphone path uses Simple Voice Chat's RNNoise denoiser. The Zevith companion mod processes outgoing microphone frames and incoming player frames locally with lightweight filtering and dynamic-level control. Restart Minecraft after changing this switch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
