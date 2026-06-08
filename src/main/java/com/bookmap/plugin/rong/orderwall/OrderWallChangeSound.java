package com.bookmap.plugin.rong.orderwall;

import java.io.ByteArrayOutputStream;

/**
 * Generates a short WAV chime for visual wall change alerts.
 */
public final class OrderWallChangeSound {

    private static final int SAMPLE_RATE = 44_100;
    private static final int DURATION_MS = 220;
    private static final double VOLUME = 0.30;

    private OrderWallChangeSound() {}

    public static byte[] createAlertSound() {
        int sampleCount = SAMPLE_RATE * DURATION_MS / 1_000;
        byte[] pcm = new byte[sampleCount * 2];

        for (int i = 0; i < sampleCount; i++) {
            double t = i / (double) SAMPLE_RATE;
            double progress = i / (double) sampleCount;
            double envelope = envelope(progress);
            double tone = Math.sin(2.0 * Math.PI * 880.0 * t)
                    + 0.55 * Math.sin(2.0 * Math.PI * 1_320.0 * t);
            short sample = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, tone * envelope * VOLUME * Short.MAX_VALUE));
            pcm[i * 2] = (byte) (sample & 0xff);
            pcm[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        writeAscii(out, "RIFF");
        writeIntLE(out, 36 + pcm.length);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeIntLE(out, 16);
        writeShortLE(out, 1);
        writeShortLE(out, 1);
        writeIntLE(out, SAMPLE_RATE);
        writeIntLE(out, SAMPLE_RATE * 2);
        writeShortLE(out, 2);
        writeShortLE(out, 16);
        writeAscii(out, "data");
        writeIntLE(out, pcm.length);
        out.write(pcm, 0, pcm.length);
        return out.toByteArray();
    }

    private static double envelope(double progress) {
        if (progress < 0.08) {
            return progress / 0.08;
        }
        if (progress > 0.78) {
            return Math.max(0.0, 1.0 - (progress - 0.78) / 0.22);
        }
        return 1.0;
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) {
        for (int i = 0; i < value.length(); i++) {
            out.write((byte) value.charAt(i));
        }
    }

    private static void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 24) & 0xff);
    }

    private static void writeShortLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }
}
