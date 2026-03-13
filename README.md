# Wall Breakout Detector - Bookmap Plugin

A Bookmap addon that detects when price breaks through large ask-side limit order walls on US equities and sends real-time signals via WebSocket.

## How It Works

1. Monitors the order book for large resting ask orders (default: >= 500,000 shares at a single price level)
2. Tracks when these walls get consumed by aggressive buying (size drops below 20% of peak)
3. When price trades above a consumed wall, broadcasts a breakout signal containing:
   - **breakoutLevel**: the price level that was broken
   - **swingLow**: the most recent pivot swing low before the breakout

## Build

Requires Java 11+ installed.

```bash
./gradlew shadowJar
```

Output JAR: `build/libs/wall-breakout-plugin-1.0-all.jar`

## Install in Bookmap

1. Open Bookmap
2. Go to **Settings** (gear icon) > **API Plugins Configuration**
3. Click **Add** and select `wall-breakout-plugin-1.0-all.jar`
4. In the popup, check **Wall Breakout Detector** and click OK
5. Add the addon to a chart: right-click the chart > **Add Addon** > **Wall Breakout Detector**

The plugin starts a WebSocket server on `localhost:8765` when attached to an instrument.

## Connect Your Trading Bot

In your browser-based trading bot, connect to the WebSocket:

```javascript
const ws = new WebSocket('ws://localhost:8765');

ws.onmessage = (event) => {
  const data = JSON.parse(event.data);

  if (data.type === 'heartbeat') {
    // Sent every 5 seconds with current price
    // { "type": "heartbeat", "price": 185.32, "timestamp": 1710345600000 }
  }

  if (data.type === 'breakout') {
    // Sent when price breaks through a large order wall
    // { "type": "breakout", "breakoutLevel": 185.50, "swingLow": 184.20, "timestamp": 1710345600000 }
  }
};
```

## Configuration

These are currently hardcoded constants in `WallBreakoutPlugin.java`:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `WS_PORT` | 8765 | WebSocket server port |
| `WALL_THRESHOLD` | 500,000 | Minimum shares at a price level to qualify as a wall |
| `WALL_CONSUMED_RATIO` | 0.20 | Wall is "consumed" when size drops below this ratio of peak |
| `SWING_LOOKBACK` | 3 | Pivot N for swing low detection (N bars on each side) |
| `BAR_SIZE` | 100 | Number of trades per tick bar for swing low calculation |
