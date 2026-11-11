package com.example.enib.domain;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;

import net.objecthunter.exp4j.Expression;

public class MathSonifier {

    private static final int SAMPLE_RATE = TimelineSignal.SAMPLE_RATE;
    private static final double PLAY_FREQ  = 440.0;   // Hz – note La4
    private static final int    WAVEFORM_SIZE = 2048;
    private static final int    DURATION_MS   = 3000;

    private AudioTrack audioTrack;
    private int totalFrames;
    private final AudioTimestamp timestamp = new AudioTimestamp();
    private long nextTimestampCheck;
    private long anchorNanos;
    private long anchorFrame;
    private long displayedFrame;
    private boolean hasTimestamp;

    // Called on the main thread together with position() and stop().
    public void playTimbre(String expression) {
        double[] waveform = buildWaveform(expression);
        if (waveform == null) {
            throw new IllegalArgumentException("Impossible de générer l’audio pour cette fonction");
        }
        play(generatePcm(waveform, SAMPLE_RATE * DURATION_MS / 1000));
    }

    public void play(short[] pcm) {
        stop();

        int bufSize = pcm.length * Short.BYTES;
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bufSize)
                .build();

        try {
            if (audioTrack.write(pcm, 0, pcm.length) != pcm.length
                    || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("Écriture audio incomplète");
            }
            totalFrames = pcm.length;
            audioTrack.play();
        } catch (RuntimeException error) {
            stop();
            throw error;
        }
    }

    public double position() {
        if (audioTrack == null) return 0;
        long frames = Integer.toUnsignedLong(audioTrack.getPlaybackHeadPosition());
        long now = System.nanoTime();
        if (nextTimestampCheck == 0 || now >= nextTimestampCheck) {
            if (audioTrack.getTimestamp(timestamp)) {
                anchorNanos = timestamp.nanoTime;
                anchorFrame = timestamp.framePosition;
                hasTimestamp = true;
            } else {
                hasTimestamp = false;
            }
            nextTimestampCheck = now + 200_000_000L;
        }
        if (hasTimestamp) {
            long estimated = anchorFrame + Math.round((now - anchorNanos) * (SAMPLE_RATE / 1e9));
            frames = Math.min(frames, estimated);
        }
        displayedFrame = Math.max(displayedFrame, frames);
        return Math.min(1.0, displayedFrame / (double) totalFrames);
    }

    public void stop() {
        if (audioTrack == null) return;
        try {
            if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) audioTrack.stop();
        } finally {
            audioTrack.release();
            audioTrack = null;
            nextTimestampCheck = 0;
            displayedFrame = 0;
            hasTimestamp = false;
        }
    }

    // Builds a single-cycle waveform (2048 samples) from the function shape
    private double[] buildWaveform(String expr) {
        try {
            Expression exp = MathFunctionParser.compile(expr);
            double[] waveform = new double[WAVEFORM_SIZE];

            for (int i = 0; i < WAVEFORM_SIZE; i++) {
                // x ∈ [-π, π] → one full period
                double x = -Math.PI + (2.0 * Math.PI * i / WAVEFORM_SIZE);
                exp.setVariable("x", x);
                double y = exp.evaluate();
                waveform[i] = Double.isFinite(y) ? y : 0.0;
            }

            // Apply Hann window to eliminate click at loop boundary
            for (int i = 0; i < WAVEFORM_SIZE; i++) {
                double window = 0.5 * (1 - Math.cos(2.0 * Math.PI * i / (WAVEFORM_SIZE - 1)));
                waveform[i] *= window;
            }

            // Normalize to [-1, 1]
            double max = 0;
            for (double v : waveform) max = Math.max(max, Math.abs(v));
            if (max > 1e-10) {
                for (int i = 0; i < WAVEFORM_SIZE; i++) waveform[i] /= max;
            } else {
                // Function is constant → produce silence
                return null;
            }

            return waveform;
        } catch (Exception e) {
            return null;
        }
    }

    // Repeats the waveform at PLAY_FREQ Hz to fill totalSamples PCM shorts
    private short[] generatePcm(double[] waveform, int totalSamples) {
        short[] pcm = new short[totalSamples];
        int fadeLen = SAMPLE_RATE / 40; // 25 ms fade in/out

        for (int i = 0; i < totalSamples; i++) {
            // Phase: how many full waveform cycles have elapsed
            double phase = (i * PLAY_FREQ) / SAMPLE_RATE;
            int idx = (int)((phase % 1.0) * WAVEFORM_SIZE);
            if (idx < 0 || idx >= WAVEFORM_SIZE) idx = 0;

            double amplitude = waveform[idx];

            // Fade envelope
            if (i < fadeLen) {
                amplitude *= (double) i / fadeLen;
            } else if (i > totalSamples - fadeLen) {
                amplitude *= (double)(totalSamples - i) / fadeLen;
            }

            pcm[i] = (short)(amplitude * Short.MAX_VALUE * 0.75);
        }
        return pcm;
    }
}
