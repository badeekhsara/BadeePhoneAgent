# Badee Phone Agent

An owner-controlled Android accessibility agent for the Samsung Galaxy S25 Ultra. The first milestone provides a secure, local command layer that can inspect the current screen and perform common touch and navigation actions.

## Current milestone

- Native Android app written in Kotlin.
- Explicit accessibility-service activation by the phone owner.
- Localhost-only command server on `127.0.0.1:8765`.
- 256-bit rotating pairing token.
- Local audit log for successful and rejected commands.
- Password fields are redacted from screen-tree output.
- Request-size, connection, concurrency, and rate limits.
- No public internet listener and no cloud credentials.

Supported actions:

`status`, `back`, `home`, `recents`, `notifications`, `quick_settings`, `tap`, `long_press`, `swipe`, `type_text`, `click_text`, `scroll_forward`, `scroll_backward`, `open_app`, `screen_tree`, and `screenshot`.

## Build

Requirements:

- JDK 17
- Android SDK 35
- Gradle 8.9 (or the included wrapper after it is generated)

```bash
./gradlew assembleDebug
```

Install the debug APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app, tap the accessibility settings button, and enable **Badee Phone Agent**. Return to the app and copy the pairing token.

## Local development connection

Forward the protected phone-local port over a USB debugging connection:

```bash
adb forward tcp:8765 tcp:8765
export BADEE_AGENT_TOKEN='paste-the-token-from-the-app'
python3 tools/phone_agent_client.py status
```

Examples:

```bash
python3 tools/phone_agent_client.py open_app --args '{"package":"com.whatsapp"}'
python3 tools/phone_agent_client.py click_text --args '{"text":"Settings"}'
python3 tools/phone_agent_client.py tap --args '{"x":720,"y":1500}'
python3 tools/phone_agent_client.py swipe --args '{"from_x":720,"from_y":2200,"to_x":720,"to_y":700}'
python3 tools/phone_agent_client.py type_text --args '{"text":"Hello from Badee Phone Agent"}'
python3 tools/phone_agent_client.py screen_tree
python3 tools/phone_agent_client.py screenshot --screenshot captures/current.jpg
```

## Security boundary

The app intentionally binds only to the phone loopback interface. Installing it does **not** expose the phone to the internet. Remote AI control requires a separate authenticated outbound relay, which will be added only after the local control and emergency-stop behavior are verified on the physical phone.

Android and individual apps may block screenshots or accessibility interaction on protected screens. The app does not bypass those operating-system protections.

## Next milestone

1. Generate and commit the Gradle wrapper, build the APK, and test it on the S25 Ultra.
2. Add an always-visible pause/emergency-stop control.
3. Add an encrypted outbound relay for approved remote sessions.
4. Connect the relay to the AI planner and require confirmation for high-impact actions.
