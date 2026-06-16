package com.example.enib.domain;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class AudioAnalyzer {

    public static class AnalysisResult {
        public final List<float[]> waveformPoints;
        public final List<float[]> spectrumPoints;
        public final double dominantFrequency;
        public final String approximateFunction;
        public final String errorMessage;
        public final boolean isSuccess;

        private AnalysisResult(List<float[]> waveform, List<float[]> spectrum,
                               double freq, String func) {
            this.waveformPoints = waveform;
            this.spectrumPoints = spectrum;
            this.dominantFrequency = freq;
            this.approximateFunction = func;
            this.errorMessage = null;
            this.isSuccess = true;
        }

        private AnalysisResult(String error) {
            this.waveformPoints = null;
            this.spectrumPoints = null;
            this.dominantFrequency = 0;
            this.approximateFunction = null;
            this.errorMessage = error;
            this.isSuccess = false;
        }

        public static AnalysisResult success(List<float[]> w, List<float[]> s, double f, String fn) {
            return new AnalysisResult(w, s, f, fn);
        }
        public static AnalysisResult failure(String e) { return new AnalysisResult(e); }
    }

    public static AnalysisResult analyze(Context context, Uri audioUri) {
        float[] samples;
        int[] sampleRateHolder = {44100};

        try {
            samples = decodePcm(context, audioUri, sampleRateHolder);
        } catch (Exception e) {
            return AnalysisResult.failure("Impossible de lire ce fichier audio : " + e.getMessage());
        }

        if (samples == null || samples.length < 512) {
            return AnalysisResult.failure("Fichier audio trop court ou format non supporté");
        }

        int sampleRate = sampleRateHolder[0];

        // Build waveform (300 points for display)
        List<float[]> waveform = buildWaveform(samples);

        // FFT analysis
        int fftSize = SimpleFFT.nextPowerOf2(Math.min(samples.length, 8192));
        double[] re = new double[fftSize];
        double[] im = new double[fftSize];

        for (int i = 0; i < fftSize; i++) {
            double window = 0.5 * (1 - Math.cos(2.0 * Math.PI * i / (fftSize - 1)));
            re[i] = (i < samples.length ? samples[i] : 0.0) * window;
        }

        SimpleFFT.fft(re, im);
        double[] mag = SimpleFFT.magnitude(re, im);

        // Find dominant frequency (skip DC bin 0, look in 20–8000 Hz)
        int minBin = (int)(20.0  * fftSize / sampleRate);
        int maxBin = (int)(8000.0 * fftSize / sampleRate);
        maxBin = Math.min(maxBin, mag.length - 1);

        int domBin = minBin;
        double domMag = 0;
        for (int i = minBin; i <= maxBin; i++) {
            if (mag[i] > domMag) { domMag = mag[i]; domBin = i; }
        }
        double dominantFreq = (double) domBin * sampleRate / fftSize;

        // Build spectrum display (up to 2000 Hz, 300 points)
        List<float[]> spectrum = buildSpectrum(mag, sampleRate, fftSize, 2000, 300);

        // Compute RMS amplitude
        double rms = 0;
        for (float s : samples) rms += s * s;
        rms = Math.sqrt(rms / samples.length);
        double amplitude = Math.min(rms * 4.0, 1.0);

        String approxFunc = buildFormula(amplitude, dominantFreq);

        return AnalysisResult.success(waveform, spectrum, dominantFreq, approxFunc);
    }

    // ── PCM decoding ──────────────────────────────────────────────────────────

    private static float[] decodePcm(Context ctx, Uri uri, int[] sampleRateOut) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;

        try {
            extractor.setDataSource(ctx, uri, null);

            int audioTrackIdx = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat tf = extractor.getTrackFormat(i);
                String mime = tf.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIdx = i;
                    fmt = tf;
                    break;
                }
            }
            if (audioTrackIdx < 0 || fmt == null) return null;

            extractor.selectTrack(audioTrackIdx);

            int channelCount = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            int sampleRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            sampleRateOut[0] = sampleRate;

            String mime = fmt.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            List<Short> pcmList = new ArrayList<>();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            int maxSamples = sampleRate * 10; // 10 s max

            while (pcmList.size() < maxSamples) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(100_000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        inBuf.clear();
                        int size = extractor.readSampleData(inBuf, 0);
                        if (size <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIdx = codec.dequeueOutputBuffer(info, 100_000);
                if (outIdx >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        codec.releaseOutputBuffer(outIdx, false);
                        break;
                    }
                    ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    outBuf.order(ByteOrder.LITTLE_ENDIAN);
                    while (outBuf.remaining() >= channelCount * 2 && pcmList.size() < maxSamples) {
                        int sum = 0;
                        for (int c = 0; c < channelCount; c++) sum += outBuf.getShort();
                        pcmList.add((short)(sum / channelCount));
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFmt = codec.getOutputFormat();
                    if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        channelCount = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        sampleRateOut[0] = sampleRate;
                    }
                } else if (inputDone) {
                    // No more output and input is done
                    break;
                }
            }

            float[] result = new float[pcmList.size()];
            for (int i = 0; i < result.length; i++) result[i] = pcmList.get(i) / (float) Short.MAX_VALUE;
            return result;

        } finally {
            if (codec != null) {
                try { codec.stop(); codec.release(); } catch (Exception ignored) {}
            }
            extractor.release();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static List<float[]> buildWaveform(float[] samples) {
        List<float[]> points = new ArrayList<>();
        int step = Math.max(1, samples.length / 300);
        int count = 0;
        for (int i = 0; i < samples.length && count < 300; i += step, count++) {
            float rms = 0;
            int n = 0;
            for (int j = i; j < Math.min(i + step, samples.length); j++, n++) rms += samples[j] * samples[j];
            rms = n > 0 ? (float) Math.sqrt(rms / n) : 0;
            float x = (float) i / samples.length * 20f - 10f; // x ∈ [-10, 10]
            points.add(new float[]{x, rms});
        }
        return points;
    }

    private static List<float[]> buildSpectrum(double[] mag, int sr, int fftSize, int maxHz, int maxPoints) {
        List<float[]> points = new ArrayList<>();
        int bins = (int)((double) maxHz * fftSize / sr);
        bins = Math.min(bins, mag.length - 1);

        double specMax = 0;
        for (int i = 1; i <= bins; i++) if (mag[i] > specMax) specMax = mag[i];

        int step = Math.max(1, bins / maxPoints);
        for (int i = 1; i <= bins; i += step) {
            float freq = (float)((double) i * sr / fftSize);
            float amp  = specMax > 0 ? (float)(mag[i] / specMax) : 0;
            points.add(new float[]{freq, amp});
        }
        return points;
    }

    private static String buildFormula(double amplitude, double freqHz) {
        // Convert Hz → angular frequency in terms of x∈[-10,10] ≈ 10 s window
        double omega = 2.0 * Math.PI * freqHz / 10.0;
        String a = String.format("%.2f", amplitude);
        String w = String.format("%.3f", omega);
        return a + " * sin(" + w + " * x)";
    }
}
