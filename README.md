<p align="center">
  <img src="assets/logo.svg" width="144" height="144" alt="Utena Mic logo">
</p>

<h2 align="center">Utena Mic</h2>

Utena Mic turns your Android phone into a Windows microphone over your local network. Use the phone's built-in mic or a compatible mic connected to Android.

## Install and connect

1. Open the [latest release](https://github.com/neura-neura/utena-mic/releases/latest). On Windows, run **UtenaMic-VAC-Setup.exe** and approve the administrator prompt. If the bundled Virtual Audio Cable Lite setup opens, accept its license and finish installing the driver. The Windows installer also includes the .NET runtime.
2. On Android, open **UtenaMic.apk** and install it. Android may ask you to allow installations from the app that opened the APK. Grant microphone access when Utena Mic asks.
3. Put both devices on the same local network. Open **Utena Mic** on both devices and press **Conectar** (Connect) on Windows first, then on Android. Leave the IP field blank for automatic discovery.
4. In the Windows app where you want to use the microphone, choose **Utena Mic (Virtual Audio Cable)** as its input. If Windows kept the driver's original name during setup, choose **Line 1 (Virtual Audio Cable)**.

The Windows installer supports x64 and Arm64 PCs running Windows 10 version 22H2 or Windows 11. The Android app requires Android 8.0 or later. VAC Lite is licensed for private, non-commercial home use. Setup includes the original VAC Lite 4.71 archive. [VAC Lite license and distribution terms](https://vac.muzychenko.net/en/manual/distrib.htm)

If automatic discovery fails, enter the IP next to the PC's Wi-Fi or Ethernet adapter in the Windows app. Setup allows TCP and UDP port 48395 for Utena Mic from the local subnet. Guest networks, Wi-Fi client isolation, or managed firewall rules can still block device-to-device connections. Audio travels unencrypted over the local network. Use a network you trust.

## Mute and update

- **Mute.** Press **Silenciar** (Mute) on either device. Both apps show the same state. Start the session from the Android app, then use its notification to mute, unmute, or disconnect while another app is open. The notification actions also work from the lock screen when Android allows them there. Allow notifications when prompted.
- **Update Windows.** Press the circular arrow in the title bar and confirm to download and start the installer.
- **Update Android.** Tap **Actualizar app** (Update app) and confirm the download. On first use, Android asks you to allow installations from Utena Mic. Return to the app to confirm the system installer. The app verifies the APK against GitHub's SHA-256 digest and the installed signing certificate.

The receiver adjusts its playback buffer when packets arrive late. It keeps their audio in order and gradually reduces the extra delay once the connection settles. A pause longer than the available audio can still cause a brief interruption while the buffer refills.

## Build from source

- **Windows installer.** With PowerShell 7, the .NET 10 SDK and NSIS 3 installed, run `./Installer/Build.ps1`. It publishes both Windows architectures with their runtimes and writes the installer to `dist/`. Pass `-MakeNsis` if NSIS is installed outside its default location.
- **Android APK.** With PowerShell 7, JDK 21, Android SDK Platform 36 and Build Tools 36.1.0 installed, run `./Android/Build.ps1`. The script writes the signed APK to `dist/`. Keep the private signing key for updates. Use `-KeyFile` to select it.
