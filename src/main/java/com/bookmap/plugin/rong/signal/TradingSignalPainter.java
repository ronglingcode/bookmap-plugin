package com.bookmap.plugin.rong.signal;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import com.bookmap.plugin.rong.patterns.Direction;

/** Advisory badge formatting; acceptance uses the immutable first-validation record. */
public final class TradingSignalPainter {
    public static List<String> badgeLines(TradingSignal signal) {
        TradingSignal.FirstValidation first = signal.firstValidation;
        String headline = signal.direction + " · " + signal.trigger.type.name().replace('_', ' ')
                + " " + quantity(signal.trigger.size);
        String thresholds = first.confirmationStrength + " · normal " + quantity(first.normalTriggerThreshold)
                + " / applied " + quantity(first.appliedTriggerThreshold);
        String reason = first.strongestConfirmation == null ? "Bid trigger meets normal requirement"
                : first.strongestConfirmation.event.type.name().replace('_', ' ') + " "
                + quantity(first.strongestConfirmation.event.size) + " · "
                + first.strongestConfirmation.ordering + " · " + first.strongestConfirmation.priceDistanceTicks + " ticks";
        if (signal.revision > 1) reason += " · updated evidence " + signal.latestConfirmationStrength;
        return List.of(headline, thresholds, reason);
    }
    public static long anchorTimeNs(TradingSignal signal) { return signal.firstValidation.eventTimeNs; }
    static String quantity(long size) {
        return size < 1000 ? Long.toString(size) : java.math.BigDecimal.valueOf(size).scaleByPowerOfTen(-3).stripTrailingZeros().toPlainString() + "K";
    }
    public static BufferedImage renderBadge(TradingSignal signal) {
        return renderLines(badgeLines(signal), signal.direction == Direction.LONG ? new Color(48, 214, 137) : new Color(255, 91, 91));
    }
    static BufferedImage renderLines(List<String> lines, Color accent) {
        Font headline = new Font("SansSerif", Font.BOLD, 13), detail = new Font("SansSerif", Font.PLAIN, 11);
        Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        int width = 0, height = 10;
        for (int i = 0; i < lines.size(); i++) {
            probe.setFont(i == 0 ? headline : detail); width = Math.max(width, probe.getFontMetrics().stringWidth(lines.get(i)));
            height += probe.getFontMetrics().getHeight() + 2;
        }
        probe.dispose(); width += 18;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB); Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(9, 13, 18, 238)); g.fillRoundRect(0, 0, width, height, 10, 10);
        g.setColor(accent); g.drawRoundRect(1, 1, width - 3, height - 3, 10, 10);
        int y = 5;
        for (int i = 0; i < lines.size(); i++) {
            g.setFont(i == 0 ? headline : detail); g.setColor(Color.WHITE);
            y += g.getFontMetrics().getAscent(); g.drawString(lines.get(i), 9, y);
            y += g.getFontMetrics().getDescent() + g.getFontMetrics().getLeading() + 2;
        }
        g.dispose(); return image;
    }
}
