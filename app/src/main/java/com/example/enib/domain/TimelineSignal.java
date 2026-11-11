package com.example.enib.domain;

import net.objecthunter.exp4j.Expression;

public final class TimelineSignal {
    public static final int SAMPLE_RATE = 44100;
    public static final int DURATION_SECONDS = 8;
    private static final double TWO_PI = 2 * Math.PI;
    private final float[] values;
    private final double xMin;
    private final double xMax;
    private final double duration;
    private final double[] accents;

    private TimelineSignal(float[] values, double xMin, double xMax, double duration) {
        this.values = values;
        this.xMin = xMin;
        this.xMax = xMax;
        this.duration = duration;
        this.accents = findAccents();
    }

    public static TimelineSignal sample(String formula) {
        return sample(formula, 0, 8, DURATION_SECONDS);
    }

    public static TimelineSignal sample(String formula, double xMin, double xMax, double duration) {
        if (!Double.isFinite(xMin) || !Double.isFinite(xMax) || xMax <= xMin
                || Math.abs(xMin) > 100000 || Math.abs(xMax) > 100000
                || !Double.isFinite(duration) || duration < 2 || duration > 30) {
            throw new IllegalArgumentException("Choisissez un intervalle croissant et une durée de 2 à 30 s.");
        }
        Expression expression = MathFunctionParser.compile(formula);
        if (!expression.validate(false).isValid()) {
            throw new IllegalArgumentException("Vérifiez la syntaxe de la fonction.");
        }
        int steps = (int) Math.ceil(duration * 400);
        float[] values = new float[steps + 1];
        boolean hasValue = false;
        for (int i = 0; i <= steps; i++) {
            checkCancelled(i);
            float value;
            try {
                double x = xMin + (xMax - xMin) * i / steps;
                value = (float) expression.setVariable("x", x).evaluate();
            } catch (ArithmeticException undefined) {
                value = Float.NaN;
            }
            values[i] = Float.isFinite(value) ? value : Float.NaN;
            hasValue |= Float.isFinite(value);
        }
        if (!hasValue) {
            throw new IllegalArgumentException("Cette fonction est indéfinie sur cet intervalle. Modifiez les réglages.");
        }
        return new TimelineSignal(values, xMin, xMax, duration);
    }

    public int size() { return values.length; }
    public float valueAt(int index) { return values[index]; }
    public float xAt(int index) { return (float) (xMin + (xMax - xMin) * index / (values.length - 1)); }
    public float xAtProgress(double progress) { return (float) (xMin + (xMax - xMin) * progress); }
    public double duration() { return duration; }

    public float valueAtProgress(double progress) {
        double index = Math.max(0, Math.min(1, progress)) * (values.length - 1);
        int left = (int) index;
        if (left == values.length - 1 || index == left) return values[left];
        double fraction = index - left;
        return (float) ((1 - fraction) * values[left] + fraction * values[left + 1]);
    }

    // A fixed, signed mapping preserves differences such as sin(x) and 2*sin(x).
    // Logarithmic compression keeps large finite values within a musical range.
    private static double musicalLevel(float value) {
        double compressed = Math.copySign(Math.log1p(Math.abs((double) value)), value);
        return Math.atan(compressed) * 2 / Math.PI;
    }

    private double[] findAccents() {
        double[] events = new double[values.length];
        double previous = Double.NaN;
        double extremum = 0;
        double travel = 0;
        int extremumIndex = 0;
        int direction = 0;
        int lastEvent = -values.length;
        int refractory = Math.max(1, (int) (0.07 * (values.length - 1) / duration));
        for (int i = 0; i < values.length; i++) {
            if (!Float.isFinite(values[i])) {
                previous = Double.NaN;
                direction = 0;
                travel = 0;
                continue;
            }
            double level = musicalLevel(values[i]);
            if (!Double.isFinite(previous)) {
                previous = extremum = level;
                extremumIndex = i;
                continue;
            }
            double delta = level - previous;
            travel += Math.abs(delta);
            int slope = Double.compare(delta, 0);
            if (direction == 0 || slope == direction) {
                extremum = level;
                extremumIndex = i;
                if (slope != 0) direction = slope;
            } else if (slope != 0 && Math.abs(level - extremum) >= 0.035) {
                if (extremumIndex - lastEvent >= refractory) {
                    events[extremumIndex] = 0.45 + 0.55 * Math.abs(extremum);
                    lastEvent = extremumIndex;
                }
                direction = slope;
                extremum = level;
                extremumIndex = i;
                travel = 0;
            }
            // Monotonic curves also sound. An accent marks each amount of vertical travel.
            if (travel >= 0.3 && i - lastEvent >= refractory) {
                events[i] = 0.35 + 0.65 * Math.abs(level);
                lastEvent = i;
                travel = 0;
            }
            previous = level;
        }
        return events;
    }

    public double accentAtProgress(double progress) {
        int index = (int) (Math.max(0, Math.min(1, progress)) * (values.length - 1));
        int history = (int) (0.18 * (values.length - 1) / duration);
        double intensity = 0;
        for (int i = Math.max(0, index - history); i <= index; i++) {
            double elapsed = (index - i) * duration / (values.length - 1);
            intensity = Math.max(intensity, accents[i] * Math.exp(-elapsed / 0.055));
        }
        return intensity;
    }

    public short[] toPcm(boolean percussion) {
        short[] pcm = new short[(int) Math.round(SAMPLE_RATE * duration)];
        double phase = 0;
        double drumPhase = 0;
        double envelope = 0;
        double gain = 0;
        int previousControl = -1;
        int fadeFrames = SAMPLE_RATE / 40;
        double decay = Math.exp(-1.0 / (SAMPLE_RATE * 0.055));
        for (int frame = 0; frame < pcm.length; frame++) {
            checkCancelled(frame);
            double progress = frame / (double) (pcm.length - 1);
            int control = (int) (progress * (values.length - 1));
            if (control != previousControl) {
                for (int i = previousControl + 1; i <= control; i++) {
                    if (accents[i] > 0) {
                        envelope = Math.max(envelope, accents[i]);
                        drumPhase = 0;
                    }
                }
                previousControl = control;
            }
            float value = valueAtProgress(progress);
            boolean defined = Float.isFinite(value);
            double level = defined ? musicalLevel(value) : 0;
            double hz = 330 * Math.pow(2, 2.5 * level);
            phase = (phase + TWO_PI * hz / SAMPLE_RATE) % TWO_PI;
            drumPhase = (drumPhase + TWO_PI * (55 + 100 * envelope) / SAMPLE_RATE) % TWO_PI;
            double targetGain = defined ? 0.7 * Math.sqrt(Math.abs(level)) : 0;
            gain += (targetGain - gain) * 0.008;
            double tone = (Math.sin(phase) + 0.22 * Math.sin(2 * phase)) / 1.22;
            double drum = Math.sin(drumPhase) * envelope;
            double sample = percussion ? drum : (0.8 * tone * gain + 0.2 * drum);
            // Undefined intervals are silent, including any previous percussion tail.
            if (!defined) envelope = 0;
            double fade = Math.min(1, Math.min(frame, pcm.length - 1 - frame) / (double) fadeFrames);
            sample = defined ? sample : 0;
            pcm[frame] = (short) (Short.MAX_VALUE * 0.72 * sample * fade);
            envelope *= decay;
        }
        return pcm;
    }

    private static void checkCancelled(int index) {
        if ((index & 1023) == 0 && Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException();
        }
    }
}
