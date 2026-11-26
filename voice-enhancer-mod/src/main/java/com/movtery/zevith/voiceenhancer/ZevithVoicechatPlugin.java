package com.movtery.zevith.voiceenhancer;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ZevithVoicechatPlugin implements VoicechatPlugin {
    private static final String CONFIG_NAME = "zevith-voice-enhancer.properties";
    private static final int FRAME_SIZE = 960;

    private final ConcurrentMap<UUID, PcmEnhancer> incomingEnhancers = new ConcurrentHashMap<>();
    private final PcmEnhancer microphoneEnhancer = new PcmEnhancer();
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
        PcmEnhancer enhancer = incomingEnhancers.computeIfAbsent(channelId, ignored -> new PcmEnhancer());
        event.setRawAudio(enhancer.process(samples));
    }

    private static boolean readEnabledSetting() {
        Path config = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_NAME);
        Properties properties = new Properties();
        if (!Files.isRegularFile(config)) {
            return false;
        }
        try (InputStream input = Files.newInputStream(config)) {
            properties.load(input);
            return Boolean.parseBoolean(properties.getProperty("enabled", "false"));
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Lightweight 48 kHz mono PCM conditioning. It removes low-frequency rumble,
     * applies gentle downward compression, and limits peaks without allocating
     * a second audio-capture or playback device.
     */
    private static final class PcmEnhancer {
        private static final double HIGH_PASS_ALPHA = 0.987;
        private static final float COMPRESSOR_THRESHOLD = 0.42f;
        private static final float MAKEUP_GAIN = 1.12f;
        private static final float LIMITER = 0.96f;

        private float previousInput;
        private float previousOutput;
        private float envelope;

        synchronized short[] process(short[] input) {
            if (input.length == 0) {
                return input;
            }

            short[] output = new short[input.length];
            for (int i = 0; i < input.length; i++) {
                float sample = input[i] / 32768.0f;

                float highPassed = (float) (HIGH_PASS_ALPHA
                        * (previousOutput + sample - previousInput));
                previousInput = sample;
                previousOutput = highPassed;

                float magnitude = Math.abs(highPassed);
                float coefficient = magnitude > envelope ? 0.18f : 0.012f;
                envelope += coefficient * (magnitude - envelope);

                float gain = MAKEUP_GAIN;
                if (envelope > COMPRESSOR_THRESHOLD) {
                    float compressedEnvelope = COMPRESSOR_THRESHOLD
                            + (envelope - COMPRESSOR_THRESHOLD) * 0.55f;
                    gain *= compressedEnvelope / Math.max(envelope, 0.0001f);
                }

                float processed = highPassed * gain;
                processed = (float) (LIMITER * Math.tanh(processed / LIMITER));
                int pcm = Math.round(processed * 32767.0f);
                output[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, pcm));
            }
            return output;
        }
    }
}
