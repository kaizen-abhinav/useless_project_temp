<img width="1280" height="640" alt="git (1)" src="https://github.com/user-attachments/assets/8920b256-2ba8-4988-b824-5351134eb4bd" />

# High Beam Glare Countermeasure System (HBGCS v2.4) ⚡

## Basic Details
### Team Name: HighBeam HUD

### Team Members
- Team Lead: Abhinav
- Member 2: Reuben Skariah

### Project Description
An overengineered, military-tactical helmet-mounted computer vision and kinematics engine that detects oncoming high-beam vehicle glare in real time, classifies headlight photometric dispersion, triangulates the oncoming driver's eye position, and directs a retaliatory helmet-mounted photon torch pulse straight into their eyes.

### The Problem (that doesn't exist)
Drivers on dark night roads constantly blind oncoming traffic with unyielding high-beam headlights, creating blinding glare, eye strain, and road rage.

### The Solution (that nobody asked for)
An automated helmet-mounted phone running OpenCV continuously monitors oncoming headlights, applies photometric spatial dispersion algorithms to detect high-beam status, triangulates the driver's eye position, and fires a retaliatory helmet torch pulse directly back at them.

## Technical Details
### Technologies/Components Used
For Software:
- Kotlin
- Jetpack Compose / CameraX (Camera2 Interop -18 EV Exposure Clamping)
- OpenCV 4.9.0 (Zero-copy Y-luminance image processing)
- Embedded Teleop HTTP WebServer (Port 8080) for Laptop Remote Override
- Custom Kinematics & Photometric Analysis Engine
- Exponential Moving Average (EMA) Servo Filters

For Hardware:
- Android Smartphone (Camera & HUD Display)
- Helmet Chin / Top Mount Assembly
- ESP32 Microcontroller (Pan/Tilt Servo & High-Intensity Torch Driver)
- Micro Servo (Horizontal Pan Gimbal)
- High-Lumen LED Photon Torch

### Implementation
For Software:
# Installation
1. Clone the repository:
   ```bash
   git clone https://github.com/kaizen-abhinav/useless_project_temp.git
   ```
2. Open the project in Android Studio.
3. Allow Gradle to sync dependencies and build the application.

# Run & Teleop Presentation Mode
1. Connect an Android device with USB debugging enabled.
2. Build and install using Android Studio or Gradle:
   ```bash
   ./gradlew app:assembleDebug
   ```
3. Grant camera permissions on the device.
4. **Laptop Teleop Remote Control Mode:**
   * Connect your laptop to the same Wi-Fi network as the phone (or the ESP32 `HelmetTracker` AP).
   * Open `http://<PHONE_IP>:8080` in Chrome/Firefox.
   * Move the Pan Azimuth slider ($0^\circ \dots 180^\circ$) or toggle **FIRE TORCH (ON)** to take manual remote control override during live hackathon demos!

### Project Documentation
For Software:

# Features & Pipeline
```
                                  [ HIGH-BEAM GLARE COUNTERMEASURE PIPELINE ]
                                                       │
 ┌─────────────────────────────────────────────────────┴────────────────────────────────────────────────────┐
 │  CameraX 640x480 @ 60 FPS   ──>   Camera2 Interop AE Clamping (-18 EV)   ──>   OpenCV Y-Luminance Mat    │
 └─────────────────────────────────────────────────────┬────────────────────────────────────────────────────┘
                                                       │
 ┌─────────────────────────────────────────────────────┴────────────────────────────────────────────────────┐
 │  1. Adaptive Morphological Filament Extraction & Symmetrical Vehicle Headlight Pair Alignment           │
 │  2. Photometric Glare Dispersion Profiler:                                                              │
 │      • Upper-hemisphere spatial cutoff ratio (E-Code / DOT beam cutoff shield analysis)                   │
 │      • Inverse-Square Law Glare Lux Estimation: Lux = (Mean_Lum * Area) / (Distance²) * k_sensor         │
 │      • Multi-factor High-Beam Confidence Engine: C_high = 0.45·Dispersion + 0.35·Luminance + 0.20·Area    │
 └─────────────────────────────────────────────────────┬────────────────────────────────────────────────────┘
                                                       │
 ┌─────────────────────────────────────────────────────┴────────────────────────────────────────────────────┐
 │  3. Kinematic RHD Driver Eye-Box Triangulation:                                                          │
 │      • Y_driver = M_y - 0.85·W_baseline  |  X_driver = M_x - 0.25·W_baseline                             │
 │      • Exponential Moving Average (EMA) Servo Smoothing: Pan(θ)                                          │
 │  4. Retaliatory Photon Countermeasure Trigger:                                                           │
 │      • IF High Beam (Confidence ≥ 60%): Fires glowing helmet torch beam vector at driver's eyes          │
 │      • IF Low Beam: Passive tracking mode                                                                │
 │  5. Laptop Teleop Web Dashboard Override (Port 8080): Overrides Pan & Torch via HTTP REST                 │
 │  6. ESP32 REST Protocol Dispatch: http://192.168.4.1/set?angle=X&light=Y                                 │
 └──────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

# ESP32 REST Payload
```text
http://192.168.4.1/set?angle=97&light=1
```

## Team Contributions
- **Abhinav:** Concept, OpenCV computer vision pipeline, Camera2 exposure clamping, photometric high-beam classifier, driver eye-box kinematics, tactical HUD overlay, embedded Teleop WebServer, and ESP32 REST protocol client.
- **Reuben Skariah:** Hardware architecture, helmet chin/top mount assembly, ESP32 pan/tilt servo gimbal wiring, power distribution, and high-lumen photon torch driver integration.

---
Made with ❤️ at TinkerHub Useless Projects

![Static Badge](https://img.shields.io/badge/TinkerHub-24?color=%23000000&link=https%3A%2F%2Fwww.tinkerhub.org%2F)
![Static Badge](https://img.shields.io/badge/UselessProjects--26-26?link=https%3A%2F%2Ftinkerhub.org%2Fevents%2F1M8ORET9A1%2Fuseless-projects-3.0)
