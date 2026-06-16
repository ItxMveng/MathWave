package com.example.enib.domain;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;

public class MathSonifier {

    private static final int SAMPLE_RATE = 44100;
    private static final double PLAY_FREQ  = 440.0;   // Hz – note La4
    private static final int    WAVEFORM_SIZE = 2048;
    private static final int    DURATION_MS   = 3000;

    private AudioTrack audioTrack;
    private Thread     playbackThread;
    private volatile boolean playing = false;

    public interface Callback {
        void onPlaybackComplete();
        void onError(String message);
    }

    public void play(String expression, Callback callback) {
        stop();

        double[] waveform = buildWaveform(expression);
        if (waveform == null) {
            if (callback != null) callback.onError("Impossible de générer l'audio pour cette fonction");
            return;
        }

        int totalSamples = SAMPLE_RATE * DURATION_MS / 1000;
        short[] pcm = generatePcm(waveform, totalSamples);

        int bufSize = pcm.length * 2; // 2 bytes per short
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

        audioTrack.write(pcm, 0, pcm.length);
        playing = true;
        audioTrack.play();

        playbackThread = new Thread(() -> {
            try {
                Thread.sleep(DURATION_MS + 200);
            } catch (InterruptedException ignored) {}
            stop();
            if (callback != null) callback.onPlaybackComplete();
        });
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    public void stop() {
        playing = false;
        if (playbackThread != null) {
            playbackThread.interrupt();
            playbackThread = null;
        }
        if (audioTrack != null) {
            try {
                if (audioTrack.getState() != AudioTrack.STATE_UNINITIALIZED) {
                    audioTrack.stop();
                }
                audioTrack.release();
            } catch (Exception ignored) {}
            audioTrack = null;
        }
    }

    public boolean isPlaying() { return playing; }

    // Builds a single-cycle waveform (2048 samples) from the function shape
    private double[] buildWaveform(String expr) {
        try {
            Expression exp = new ExpressionBuilder(expr).variable("x").build();
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
