# 🛡️ Suraksha AI — Women's Safety Android App

> **AI Powered Personal Safety** — Stay Safe. Stay Smart.

---

## 📱 What's Built

| Screen | Description |
|--------|-------------|
| **Splash** | Animated logo with glow-ring effect |
| **Onboarding** | First-run emergency contact setup |
| **Home Dashboard** | Live heart-rate ring, BP card, motion indicator, SOS button |
| **SOS Activity** | 10-sec countdown → full SOS activated screen with checklist |
| **Live Tracking** | GPS coordinates display (Google Maps placeholder) |
| **Friends** | View / remove emergency contacts |
| **Settings** | Add contacts, configure HR alert thresholds |

---

## 🏗️ Project Structure

```
SurakshaiAI/
├── app/src/main/
│   ├── AndroidManifest.xml
│   └── java/com/suraksha/ai/
│       ├── SurakshaApp.java          ← App class, notification channels
│       ├── model/
│       │   ├── SafetyStatus.java     ← SAFE / WARNING / DANGER / SOS_ACTIVE
│       │   ├── SensorReading.java    ← Biometric + location snapshot
│       │   └── EmergencyContact.java ← Contact data model
│       ├── utils/
│       │   ├── PrefsManager.java     ← All SharedPreferences in one place
│       │   ├── AlertEngine.java      ← Rule-based threat evaluator  ← EXTEND HERE
│       │   └── SmsHelper.java        ← Emergency SMS sender
│       ├── service/
│       │   ├── MonitoringService.java ← Foreground service: HR + Accel + GPS
│       │   └── BootReceiver.java      ← Auto-restart after reboot
│       └── ui/
│           ├── SplashActivity.java
│           ├── OnboardingActivity.java
│           ├── MainActivity.java      ← Bottom-nav host
│           ├── home/HomeFragment.java ← Main dashboard
│           ├── tracking/TrackingFragment.java
│           ├── friends/FriendsFragment.java
│           ├── alert/SosActivity.java ← Full-screen SOS
│           └── settings/
│               ├── SettingsFragment.java
│               └── ContactsAdapter.java
```

---

## 🚀 Setup Instructions

### 1. Open in Android Studio
```
File → Open → select the SurakshaiAI/ folder
```

### 2. Sync Gradle
Android Studio will prompt to sync. Click **Sync Now**.

### 3. Add Google Maps API Key (for Tracking screen)
In `app/src/main/res/values/strings.xml`, add:
```xml
<string name="google_maps_key">YOUR_API_KEY_HERE</string>
```
And in `AndroidManifest.xml` inside `<application>`:
```xml
<meta-data
    android:name="com.google.android.geo.API_KEY"
    android:value="@string/google_maps_key"/>
```

### 4. Replace Launcher Icons
Run `generate_icons.py` to regenerate placeholders, or replace:
```
app/src/main/res/mipmap-*/ic_launcher.png
app/src/main/res/mipmap-*/ic_launcher_round.png
```

### 5. Build & Run
Connect a physical Android device (heart-rate sensor won't work on emulator).
```
Run → Run 'app'
```

---

## 📡 How Sensors Work

### Heart Rate
- Uses Android `TYPE_HEART_RATE` sensor (available on most modern phones)
- On phones **without** the sensor, HR shows `--` and no HR-based alerts fire
- For reliable readings: user must place finger on rear camera/sensor
- **Better option (future):** pair a BLE wearable (smartwatch, fitness band)

### Blood Pressure
- **Cannot be measured by phone hardware alone**
- The `bpSystolic / bpDiastolic` fields in `SensorReading` are reserved
- **Integration path:** connect via BLE to an Omron / A&D cuff
  - Use Android `BluetoothLeScanner` → GATT profile → read BP characteristic

### Accelerometer / Motion
- Always available — detects sudden movement spikes > 2.5G
- Triggers `WARNING` status (yellow ring)

### GPS
- Uses `FusedLocationProviderClient` for battery-efficient location
- Updates every 10 seconds when monitoring is active

---

## 🔧 How to Extend / Modify

### Change Alert Rules
Edit `AlertEngine.java`:
```java
public SafetyStatus evaluate(SensorReading r) {
    // Add your logic here
    // e.g., sustained high HR + rapid motion = DANGER
}
```

### Add a New Screen
1. Create `MyFragment.java` in the right `ui/` sub-package
2. Create `fragment_my.xml` in `res/layout/`
3. Add it to `res/navigation/nav_graph.xml`
4. Add a menu item to `res/menu/bottom_nav_menu.xml`

### Change Thresholds at Runtime
Users can update via **Settings → Heart Rate Thresholds**.
Values saved to `SharedPreferences` and read by `AlertEngine` on next service start.

### Add BLE Blood Pressure Monitor
1. Scan for BLE devices in a new `BleManager.java`
2. Connect to BP cuff GATT service (UUID: `0x1810`)
3. Write received values into `SensorReading.bpSystolic / bpDiastolic`
4. Call `evaluateAndBroadcast()` in `MonitoringService`

### Add ML Threat Detection
Replace or augment `AlertEngine.evaluate()`:
```java
// Load a TFLite model
Interpreter tflite = new Interpreter(loadModelFile());
// Pass sensor features as float array
float[][] output = new float[1][1];
tflite.run(inputFeatures, output);
if (output[0][0] > 0.7f) return SafetyStatus.DANGER;
```

---

## 🎨 UI Color System

| Token | Value | Usage |
|-------|-------|-------|
| `brand_blue` | `#00D4FF` | App title, icons, links |
| `safe_green` | `#00E676` | SAFE state ring & badge |
| `warning_yellow` | `#FFD600` | WARNING / ALERT state |
| `danger_red` | `#FF1744` | DANGER state |
| `sos_red` | `#FF0033` | SOS button & activated screen |
| `bg_dark` | `#050D1A` | Main background |
| `bg_card` | `#0D1B2E` | Card backgrounds |

All colors defined in `res/values/colors.xml`.

---

## 🔮 Planned Next Features

- [ ] **Camera PPG** — measure HR using rear camera flash
- [ ] **BLE BP cuff** integration (Omron HEM-9200T / A&D UA-651BLE)
- [ ] **Google Maps embed** for live tracking with trail
- [ ] **Audio/Video recording** during SOS (saved locally)
- [ ] **Anti-theft mode** — lock device + siren on unauthorized access
- [ ] **Family network** — share safety status with trusted circle
- [ ] **ML model** — smarter threat detection from sensor patterns
- [ ] **Shake-to-SOS** — three rapid shakes trigger emergency
- [ ] **Stealth mode** — app looks like a calculator externally

---

## 📋 Permissions Explained

| Permission | Why |
|------------|-----|
| `BODY_SENSORS` | Read heart-rate sensor |
| `ACCESS_FINE_LOCATION` | GPS for emergency SMS |
| `SEND_SMS` | Alert emergency contacts |
| `FOREGROUND_SERVICE` | Keep monitoring running in background |
| `CAMERA` | Future: PPG heart-rate via camera |
| `RECORD_AUDIO` | Future: Audio recording during SOS |
| `RECEIVE_BOOT_COMPLETED` | Restart monitoring after reboot |

---

*Built with ❤️ for women's safety. Because your safety matters.*
