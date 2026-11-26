/*
 * Copyright (C) 2026 contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package com.movtery.zalithlauncher.ui.screens.content.settings

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.addons.modloader.ModLoader
import com.movtery.zalithlauncher.game.path.GamePathManager
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.screens.NestedNavKey
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.ui.screens.TitledNavKey
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.zip.ZipFile

private const val TAG = "VoiceEnhancer"
private const val COMPANION_MOD_ID = "zevith_voice_enhancer"
private const val COMPANION_MOD_ASSET = "zevith-voice-enhancer.jar"
private const val SUPPORTED_MINECRAFT_VERSION = "1.21.11"

private data class VoiceChatInstall(
    val name: String,
    val modsDirectory: File,
    val modFile: File,
    val configFile: File,
    val enhancerConfigFile: File,
    val isFabric: Boolean,
    val denoiserEnabled: Boolean,
    val nativeAudioEnabled: Boolean,
    val enhancerEnabled: Boolean,
    val enhancerInstalled: Boolean
)

private fun hasFabricModId(file: File, modId: String): Boolean {
    return try {
        ZipFile(file).use { zip ->
            val metadata = zip.getEntry("fabric.mod.json")?.let { entry ->
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }.orEmpty()
            Regex("\"id\"\\s*:\\s*\"${Regex.escape(modId)}\"").containsMatchIn(metadata)
        }
    } catch (e: IOException) {
        Logger.warning(TAG, "Could not inspect mod metadata in ${file.absolutePath}", e)
        false
    } catch (e: SecurityException) {
        Logger.warning(TAG, "Permission denied while inspecting ${file.absolutePath}", e)
        false
    }
}

private fun simpleVoiceChatUsesFabric(file: File): Boolean? {
    return try {
        ZipFile(file).use { zip ->
            val fabricMetadata = zip.getEntry("fabric.mod.json")?.let { entry ->
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }.orEmpty()
            if (Regex("\"id\"\\s*:\\s*\"voicechat\"").containsMatchIn(fabricMetadata)) {
                return true
            }

            val forgeMetadata = zip.getEntry("META-INF/mods.toml")?.let { entry ->
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }.orEmpty()
            if (Regex("modId\\s*=\\s*\"voicechat\"").containsMatchIn(forgeMetadata)) {
                false
            } else {
                null
            }
        }
    } catch (e: IOException) {
        Logger.warning(TAG, "Could not inspect Simple Voice Chat metadata in ${file.absolutePath}", e)
        null
    } catch (e: SecurityException) {
        Logger.warning(TAG, "Permission denied while inspecting ${file.absolutePath}", e)
        null
    }
}

private fun readProperties(file: File): Properties {
    val properties = Properties()
    if (!file.isFile) return properties

    try {
        file.inputStream().use(properties::load)
    } catch (e: IOException) {
        Logger.warning(TAG, "Could not read settings from ${file.absolutePath}", e)
        throw e
    } catch (e: SecurityException) {
        Logger.warning(TAG, "Permission denied while reading ${file.absolutePath}", e)
        throw e
    }
    return properties
}

private fun findVoiceChatInstalls(gameDirectory: File, profileName: String): List<VoiceChatInstall> {
    val modsDirectory = File(gameDirectory, "mods")
    if (!modsDirectory.isDirectory) return emptyList()
    val modFiles = modsDirectory.listFiles()
        ?: throw IOException("Could not read the mods directory: ${modsDirectory.absolutePath}")

    return modFiles.asSequence()
        .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
        .mapNotNull { mod ->
            val isFabric = simpleVoiceChatUsesFabric(mod) ?: return@mapNotNull null
            val configDirectory = File(gameDirectory, "config")
            val voiceChatConfig = File(configDirectory, "voicechat/voicechat-client.properties")
            val enhancerConfig = File(configDirectory, "zevith-voice-enhancer.properties")
            val voiceChatProperties = readProperties(voiceChatConfig)
            val enhancerProperties = readProperties(enhancerConfig)
            val enhancerInstalled = modsDirectory.listFiles()?.any {
                it.isFile && it.extension.equals("jar", ignoreCase = true) &&
                    hasFabricModId(it, COMPANION_MOD_ID)
            } == true

            VoiceChatInstall(
                name = profileName,
                modsDirectory = modsDirectory,
                modFile = mod,
                configFile = voiceChatConfig,
                enhancerConfigFile = enhancerConfig,
                isFabric = isFabric,
                denoiserEnabled = voiceChatProperties.getProperty("denoiser", "false").equals("true", true),
                nativeAudioEnabled = voiceChatProperties.getProperty("use_natives", "true").equals("true", true),
                enhancerEnabled = enhancerProperties.getProperty("enabled", "false").equals("true", true),
                enhancerInstalled = enhancerInstalled
            )
        }
        .distinctBy { it.modFile.absolutePath }
        .toList()
}

private fun installBundledCompanion(context: Context, install: VoiceChatInstall) {
    if (install.enhancerInstalled) return

    Files.createDirectories(install.modsDirectory.toPath())
    val target = File(install.modsDirectory, COMPANION_MOD_ASSET)
    val temporary = Files.createTempFile(
        install.modsDirectory.toPath(),
        "zevith-voice-enhancer-",
        ".jar"
    )
    try {
        context.assets.open(COMPANION_MOD_ASSET).use { input ->
            Files.newOutputStream(temporary).use { output -> input.copyTo(output) }
        }
        if (!hasFabricModId(temporary.toFile(), COMPANION_MOD_ID)) {
            throw IOException("The bundled Voice Enhancer companion mod is invalid.")
        }

        try {
            Files.move(
                temporary,
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        try {
            Files.deleteIfExists(temporary)
        } catch (e: IOException) {
            Logger.warning(TAG, "Could not remove temporary companion mod file $temporary", e)
        }
    }
}

private fun setVoiceEnhancerEnabled(
    context: Context,
    installs: List<VoiceChatInstall>,
    enabled: Boolean
) {
    if (installs.isEmpty()) throw IllegalStateException("Simple Voice Chat was not found.")

    if (enabled) {
        if (installs.any { !it.isFabric }) {
            throw IllegalStateException("The Zevith companion currently supports Fabric only.")
        }
        installs.forEach { installBundledCompanion(context, it) }
    }

    installs.forEach { install ->
        val voiceChatProperties = readProperties(install.configFile)
        voiceChatProperties.setProperty("denoiser", enabled.toString())
        if (enabled) voiceChatProperties.setProperty("use_natives", "true")
        Files.createDirectories(install.configFile.parentFile!!.toPath())
        install.configFile.outputStream().use {
            voiceChatProperties.store(it, "Zevith Voice Enhancer settings")
        }

        val enhancerProperties = readProperties(install.enhancerConfigFile)
        enhancerProperties.setProperty("enabled", enabled.toString())
        Files.createDirectories(install.enhancerConfigFile.parentFile!!.toPath())
        install.enhancerConfigFile.outputStream().use {
            enhancerProperties.store(it, "Zevith Voice Enhancer settings")
        }
    }
}

private fun supportsCompanion(version: Version?, installs: List<VoiceChatInstall>): Boolean {
    val versionInfo = version?.getVersionInfo() ?: return false
    return versionInfo.minecraftVersion == SUPPORTED_MINECRAFT_VERSION &&
        versionInfo.hasLoader(ModLoader.FABRIC) &&
        installs.isNotEmpty() &&
        installs.all { it.isFabric }
}

@Composable
fun VoiceEnhancerScreen(
    key: NestedNavKey.Settings,
    settingsScreenKey: TitledNavKey?,
    mainScreenKey: TitledNavKey?
) {
    val context = LocalContext.current
    val gamePath by GamePathManager.currentPath.collectAsState()
    val selectedVersion by VersionsManager.currentVersion.collectAsState()
    val activeVersion = selectedVersion?.takeIf {
        File(it.getGameHome()).absolutePath == File(gamePath).absolutePath
    }
    val gameDirectory = activeVersion?.getGameDir() ?: File(gamePath)
    val profileName = activeVersion?.getVersionName()
        ?: stringResource(R.string.voice_enhancer_default_profile)
    val scope = rememberCoroutineScope()
    var installs by remember { mutableStateOf<List<VoiceChatInstall>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val settingsEnabled = installs.isNotEmpty() && installs.all {
        it.denoiserEnabled && it.nativeAudioEnabled && it.enhancerEnabled
    }
    val companionReady = installs.isNotEmpty() && installs.all { it.enhancerInstalled }
    val supportedProfile = supportsCompanion(activeVersion, installs)

    LaunchedEffect(gameDirectory.absolutePath, profileName) {
        isLoading = true
        error = null
        try {
            installs = withContext(Dispatchers.IO) {
                findVoiceChatInstalls(gameDirectory, profileName)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.error(TAG, "Could not scan Simple Voice Chat in ${gameDirectory.absolutePath}", e)
            installs = emptyList()
            error = e.message ?: "Could not scan the selected game profile."
        } finally {
            isLoading = false
        }
    }

    val stateDescription = when {
        installs.isEmpty() -> stringResource(R.string.voice_enhancer_not_detected)
        !supportedProfile -> when {
            activeVersion?.getVersionInfo() == null ->
                stringResource(R.string.voice_enhancer_select_supported_profile)
            activeVersion.getVersionInfo()?.minecraftVersion != SUPPORTED_MINECRAFT_VERSION ->
                stringResource(
                    R.string.voice_enhancer_unsupported_version,
                    SUPPORTED_MINECRAFT_VERSION
                )
            installs.any { !it.isFabric } ->
                stringResource(R.string.voice_enhancer_fabric_only)
            else -> stringResource(R.string.voice_enhancer_select_supported_profile)
        }
        settingsEnabled && companionReady ->
            stringResource(R.string.voice_enhancer_configured)
        settingsEnabled ->
            stringResource(R.string.voice_enhancer_companion_missing)
        else ->
            stringResource(R.string.voice_enhancer_enable_hint)
    }

    BaseScreen(
        Triple(key, mainScreenKey, false),
        Triple(NormalNavKey.Settings.VoiceEnhancer, settingsScreenKey, false)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.voice_enhancer_title), style = MaterialTheme.typography.headlineSmall)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.voice_enhancer_audio_paths),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stateDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settingsEnabled,
                            enabled = installs.isNotEmpty() && !isLoading && !isSaving &&
                                (supportedProfile || settingsEnabled),
                            onCheckedChange = { checked ->
                                isSaving = true
                                error = null
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            if (checked && !supportedProfile) {
                                                throw IllegalStateException(
                                                    context.getString(R.string.voice_enhancer_select_supported_profile)
                                                )
                                            }
                                            setVoiceEnhancerEnabled(context, installs, checked)
                                        }
                                        installs = withContext(Dispatchers.IO) {
                                            findVoiceChatInstalls(gameDirectory, profileName)
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Logger.error(TAG, "Could not update Voice Enhancer settings", e)
                                        error = e.message
                                            ?: context.getString(R.string.voice_enhancer_update_failed)
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            }
                        )
                    }
                }
            }

            Text(
                stringResource(R.string.voice_enhancer_detected_heading),
                style = MaterialTheme.typography.titleMedium
            )
            when {
                isLoading -> Text(stringResource(R.string.voice_enhancer_scanning))
                installs.isEmpty() -> Text(
                    stringResource(R.string.voice_enhancer_not_detected),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> installs.forEach { install ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.voice_enhancer_simple_voice_chat),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(install.modFile.name, style = MaterialTheme.typography.bodySmall)
                            Text(
                                install.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (install.enhancerInstalled) {
                                    stringResource(R.string.voice_enhancer_companion_detected)
                                } else {
                                    stringResource(R.string.voice_enhancer_companion_not_installed)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (install.denoiserEnabled && install.nativeAudioEnabled) {
                                    stringResource(R.string.voice_enhancer_microphone_configured)
                                } else {
                                    stringResource(R.string.voice_enhancer_microphone_off)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (install.enhancerEnabled && install.enhancerInstalled) {
                                    stringResource(R.string.voice_enhancer_incoming_configured)
                                } else {
                                    stringResource(R.string.voice_enhancer_incoming_unavailable)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.voice_enhancer_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
