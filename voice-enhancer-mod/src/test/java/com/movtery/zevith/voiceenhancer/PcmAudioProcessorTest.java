package com.movtery.zevith.voiceenhancer;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertTrue;

public final class PcmAudioProcessorTest {
    @Test
    public void silenceRemainsSilent() {
        short[] output = new PcmAudioProcessor().process(new short[960]);

        for (short sample : output) {
            assertTrue(sample == 0);
        }
    }

    @Test
    public void outputStaysWithinPcmRange() {
        short[] clippedSignal = new short[960];
        for (int i = 0; i < clippedSignal.length; i++) {
            clippedSignal[i] = (short) ((i & 1) == 0 ? Short.MAX_VALUE : Short.MIN_VALUE);
        }

        short[] output = new PcmAudioProcessor().process(clippedSignal);

        for (short sample : output) {
            assertTrue(sample >= Short.MIN_VALUE);
            assertTrue(sample <= Short.MAX_VALUE);
        }
    }

    @Test
    public void highPassRejectsSteadyOffsetAcrossFrames() {
        PcmAudioProcessor processor = new PcmAudioProcessor();
        short[] steadyOffset = new short[960 * 4];
        Arrays.fill(steadyOffset, (short) 12_000);

        short[] output = processor.process(steadyOffset);

        assertTrue(Math.abs((int) output[output.length - 1]) < 10);
    }
}
