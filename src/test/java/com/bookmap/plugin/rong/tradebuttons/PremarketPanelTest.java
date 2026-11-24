package com.bookmap.plugin.rong.tradebuttons;

import com.bookmap.plugin.rong.SignalWebSocketServer;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.ArrayList;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PremarketPanelTest {
    @Test void narrowTicketFitsLimitButtonsAndEditableOnePercentDefault() throws Exception {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        try {
            SwingUtilities.invokeAndWait(() -> {
                PremarketTradingPanel panel = new PremarketTradingPanel("AMD", server);
                try {
                    panel.setSize(245, panel.getPreferredSize().height); layout(panel);
                    ArrayList<JButton> buttons = new ArrayList<>(); ArrayList<JTextField> inputs = new ArrayList<>();
                    inspect(panel, buttons, inputs);
                    for (JButton button : buttons) assertNull(button.getToolTipText());
                    for (JTextField field : inputs) assertNull(field.getToolTipText());
                    assertEquals(java.util.List.of("Buy Now", "Buy Bid", "Sell Now", "Sell Ask", "Buy Limit", "Sell Limit", "Flatten"),
                            buttons.stream().map(JButton::getText).collect(java.util.stream.Collectors.toList()));
                    assertEquals(3, inputs.size()); assertEquals("", inputs.get(0).getText()); assertEquals("1", inputs.get(1).getText()); assertEquals("", inputs.get(2).getText());
                    server.setPremarketMode(() -> true);
                    ArrayList<com.google.gson.JsonObject> sent = new ArrayList<>(); server.setTradingDispatch(sent::add);
                    inputs.get(0).setText("25");
                    com.google.gson.JsonObject hotkey = new com.google.gson.JsonObject(); hotkey.addProperty("symbol", "AMD"); hotkey.addProperty("keyCode", "KeyL"); hotkey.addProperty("price", 99);
                    server.dispatchTradingAction(hotkey);
                    assertEquals(25, sent.get(0).get("quantity").getAsInt(), "Hover + L reads the actual Shares textbox");
                    inputs.get(0).setText("");
                    BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics(); panel.paint(graphics); graphics.dispose();
                    Path output = Path.of("build", "private", "premarket-panel.png"); Files.createDirectories(output.getParent());
                    ImageIO.write(image, "png", output.toFile());
                } catch (java.io.IOException error) { throw new RuntimeException(error); }
                finally { panel.close(); }
            });
        } finally { server.shutdown(); }
    }
    private static void layout(Container parent) { parent.doLayout(); for (Component child : parent.getComponents()) if (child instanceof Container) layout((Container) child); }
    private static void inspect(Container parent, ArrayList<JButton> buttons, ArrayList<JTextField> inputs) {
        for (Component child : parent.getComponents()) {
            assertTrue(child.getX() >= 0 && child.getX() + child.getWidth() <= parent.getWidth(), "Clipped control: " + child);
            if (child instanceof JButton) { buttons.add((JButton) child); assertTrue(child.getWidth() >= 70); }
            if (child instanceof JTextField) inputs.add((JTextField) child);
            if (child instanceof JLabel) assertTrue(child.getWidth() >= child.getPreferredSize().width, "Clipped label text: " + child);
            if (child instanceof Container) inspect((Container) child, buttons, inputs);
        }
    }
}
