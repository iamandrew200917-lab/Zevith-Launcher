package com.movtery.zevith.voiceenhancer;

final class PcmAudioProcessor {
    private static final double HIGH_PASS_ALPHA = 0.987;
    private static final float COMPRESSOR_THRESHOLD = 0.42f;
    private static final float MAKEUP_GAIN = 1.12f;
    private static final float LIMITER = 0.96f;

    private float previousInput;
    private float previousOutput;
    private float envelope;

    synchronized short[] process(short[] input) {
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
