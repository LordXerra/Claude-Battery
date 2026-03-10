# Claude Battery

A macOS menu bar app that monitors your Claude AI usage in real time, displayed as a battery-style icon with two progress bars — one for your 5-hour usage window and one for your 7-day usage window.

Developed by Tony Brice.

---

## Features

- **Menu bar icon** — battery icon with two gradient bars (green → yellow → red) showing usage at a glance
- **Hover popup** — detailed breakdown of hourly and weekly usage with time until reset
- **Dashboard window** — circular gauges with full token counts and reset times
- **Web Session mode** — connects to claude.ai using your browser session cookie; no API credits required
- **API Key mode** — connects via the Anthropic API using rate-limit headers
- **Notifications** — optional warnings at 80% and 95% usage
- **Auto-start on login** — runs silently in the background via a macOS LaunchAgent
- **Single instance** — launching a second copy exits silently

---

## Requirements

- macOS 11 or later
- Java 21 (Temurin recommended) — or use the bundled `.app` which includes its own JRE
- A claude.ai account (Pro plan recommended for web session mode)

---

## Installation

### Option A — Launchpad (recommended)

The app is pre-built and installed at `/Applications/Claude Battery.app`. It will appear in Launchpad and starts automatically on login.

### Option B — Build from source

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  gradle build

java -jar build/libs/claude-battery-1.0.0.jar
```

To rebuild the `.app` bundle and install to `/Applications`:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home gradle build

jpackage \
  --type app-image \
  --name "Claude Battery" \
  --app-version "1.0.0" \
  --input build/libs \
  --main-jar claude-battery-1.0.0.jar \
  --main-class com.claudebattery.Main \
  --dest /tmp/claude-battery-app

cp -r "/tmp/claude-battery-app/Claude Battery.app" /Applications/
```

---

## Setup

On first launch, the Settings dialog opens automatically.

### Web Session mode (recommended — no API credits needed)

1. Open **claude.ai** in your browser
2. Open DevTools (`F12`) → **Application** → **Cookies** → `https://claude.ai`
3. Copy the value of the cookie named `sessionKey`
4. Paste it into Settings → **Web Session** → Session Key → **Test** → **Save**

The session key is saved to `~/.claude-battery/config.json` and persists across reboots. You only need to refresh it if your claude.ai session expires.

### API Key mode

1. Get your API key from [console.anthropic.com](https://console.anthropic.com)
2. Paste it into Settings → **API Key** → **Test** → **Save**

> Note: API key mode requires API credits separate from a Claude Pro subscription.

---

## Usage

| Action | Result |
|--------|--------|
| Click tray icon | Opens context menu |
| Double-click tray icon | Opens Dashboard |
| Hover over tray icon | Shows usage popup (may vary on macOS) |
| Menu → Open Dashboard | Full circular gauge view |
| Menu → Refresh Now | Forces an immediate poll |
| Menu → Settings… | Configure session key, limits, notifications |
| Menu → Quit Claude Battery | Exits the app |

---

## Configuration

Settings are stored at `~/.claude-battery/config.json`:

| Setting | Default | Description |
|---------|---------|-------------|
| `useWebSession` | `false` | Use claude.ai session cookie instead of API key |
| `sessionKey` | — | claude.ai browser session cookie value |
| `apiKey` | — | Anthropic API key |
| `hourlyTokenLimit` | `40000` | Token limit for hourly bar (API mode) |
| `weeklyTokenLimit` | `1000000` | Token limit for weekly bar (API mode) |
| `pollIntervalSeconds` | `300` | How often to check usage (minimum 30s) |
| `notifyAt80` | `true` | Show notification at 80% usage |
| `notifyAt95` | `true` | Show notification at 95% usage |

---

## Auto-start

The app registers a LaunchAgent at:

```
~/Library/LaunchAgents/com.claudebattery.app.plist
```

To disable auto-start:

```bash
launchctl unload ~/Library/LaunchAgents/com.claudebattery.app.plist
```

To re-enable:

```bash
launchctl load ~/Library/LaunchAgents/com.claudebattery.app.plist
```

---

## Project Structure

```
src/main/java/com/claudebattery/
├── Main.java                 # Entry point, single-instance lock
├── Config.java               # Configuration model
├── ConfigManager.java        # Read/write config.json
├── UsageStore.java           # Persist hourly/weekly counters
├── UsageSnapshot.java        # Immutable usage state record
├── ClaudeUsageService.java   # Core polling service
├── ClaudeWebClient.java      # claude.ai session cookie client
├── AnthropicClient.java      # Anthropic API client
├── SystemTrayApp.java        # Tray icon, menus, hover popup
├── BatteryIconRenderer.java  # Battery icon renderer
├── HoverPopup.java           # Hover detail popup
├── DashboardWindow.java      # Full dashboard with circular gauges
├── CircularGaugePanel.java   # Circular arc gauge component
├── SettingsDialog.java       # Settings dialog
└── NotificationManager.java  # macOS notifications
```

---

## License

MIT — free to use, modify, and distribute.
