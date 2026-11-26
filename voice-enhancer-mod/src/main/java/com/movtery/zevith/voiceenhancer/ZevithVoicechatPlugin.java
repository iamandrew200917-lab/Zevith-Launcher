package com.movtery.zevith.voiceenhancer;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ZevithVoicechatPlugin implements VoicechatPlugin {
    private static final String CONFIG_NAME = "zevith-voice-enhancer.properties";
    private static final int FRAME_SIZE = 960;
    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger("Zevith Voice Enhancer");

    private final ConcurrentMap<UUID, PcmAudioProcessor> incomingEnhancers = new ConcurrentHashMap<>();
    private final PcmAudioProcessor microphoneEnhancer = new PcmAudioProcessor();
    private volatile boolean enabled;

    @Override
    public String getPluginId() {
        return "zevith_voice_enhancer";
    }

    @Override
    public void initialize(VoicechatApi api) {
        enabled = readEnabledSetting();
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        if (!enabled) {
            return;
        }

        registration.registerEvent(ClientSoundEvent.class, this::processMicrophone);
        registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class, this::processIncoming);
        registration.registerEvent(ClientReceiveSoundEvent.LocationalSound.class, this::processIncoming);
        registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class, this::processIncoming);
    }

    private void processMicrophone(ClientSoundEvent event) {
        short[] samples = event.getRawAudio();
        if (samples == null || samples.length == 0) {
            return;
        }
        event.setRawAudio(microphoneEnhancer.process(samples));
    }

    private void processIncoming(ClientReceiveSoundEvent event) {
        short[] samples = event.getRawAudio();
        if (samples == null || samples.length != FRAME_SIZE) {
            return;
        }

        UUID channelId = event.getId();
        if (channelId == null) {
            return;
        }
        PcmAudioProcessor enhancer = incomingEnhancers.computeIfAbsent(channelId, ignored -> new PcmAudioProcessor());
        event.setRawAudio(enhancer.process(samples));
    }

    private static boolean readEnabledSetting() {
        Path config = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_NAME);
        Properties properties = new Properties();
        try {
            if (!Files.isRegularFile(config)) {
                return false;
            }
            try (InputStream input = Files.newInputStream(config)) {
                properties.load(input);
            }
            return Boolean.parseBoolean(properties.getProperty("enabled", "false"));
        } catch (IOException | SecurityException | IllegalArgumentException exception) {
            LOGGER.log(Level.WARNING, "Could not read Voice Enhancer settings; audio processing remains disabled.", exception);
            return false;
        }
    }
}
