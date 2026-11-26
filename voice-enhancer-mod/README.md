# Zevith Voice Enhancer companion mod

This is a client-side Fabric addon for Simple Voice Chat. It provides the runtime audio hooks that the Android launcher itself cannot execute, because Minecraft runs in a separate JVM.

## What it processes

- **Outgoing microphone audio:** Simple Voice Chat's built-in RNNoise denoiser is enabled by the launcher. This addon then applies light low-frequency filtering and dynamic-level control to the captured PCM frames.
- **Incoming player audio:** the addon processes entity, locational, and static receive frames locally before Simple Voice Chat plays them. It applies light low-frequency filtering, gentle compression, and peak limiting.

The addon does not open a second microphone or speaker device and does not send audio to an external service. Incoming audio is processed only on this client; other players do not need the addon.

## Compatibility

- Minecraft Java 1.21.11
- Fabric Loader 0.18.4 or newer
- Simple Voice Chat API 2.6.25 (runtime dependency: Simple Voice Chat 2.6.x)

This companion module currently targets Fabric. Forge, NeoForge, and Quilt builds are not included.

## Build

From the repository root:

```bash
./gradlew -p voice-enhancer-mod build
```

The output JAR is written to:

```
voice-enhancer-mod/build/libs/zevith-voice-enhancer-0.1.0.jar
```

## Install

The launcher bundles this JAR as `assets/zevith-voice-enhancer.jar` inside every APK and
copies it into the active instance's `mods` directory when the Voice Enhancer switch is
turned on. Outdated copies (including older versioned file names) are replaced, because
two JARs declaring `zevith_voice_enhancer` would prevent Fabric from starting.

To install it by hand instead, copy the built JAR into the same Minecraft instance `mods`
directory as Simple Voice Chat, then launch Minecraft once.

The Zevith launcher's Voice Enhancer switch writes:

- `config/voicechat/voicechat-client.properties`: enables Simple Voice Chat's `denoiser` and native audio support.
- `config/zevith-voice-enhancer.properties`: controls this companion addon's `enabled` setting.

Restart Minecraft after changing the switch. If the companion JAR is absent, the microphone denoiser can still be configured, but incoming-player processing will not run.

## Processing notes

The PCM processor is intentionally lightweight and uses only the public Simple Voice Chat client events. It is an initial clarity-oriented filter, not a studio noise-removal system. Audio quality and latency still require in-game listening tests on supported devices.
