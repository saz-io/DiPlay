# Android Auto from an Android phone (experimental)

DiPlay can act as the head unit for a phone that uses **wireless Android Auto**. The phone shows its Android Auto screen on the car's display, plays sound through the car, and takes touch and media keys from the car. It runs beside wireless CarPlay and is off by default.

> **Status: untested on a phone or in a car.** The protocol code is checked by automated tests in which a scripted fake phone speaks the protocol to DiPlay over a local socket, with real TLS. Those tests show that DiPlay is internally consistent with the published protocol definitions. They do not show that a real phone accepts DiPlay, and nobody has yet connected a phone. Treat everything here as a starting point for testing.

## What you need

1. An Android phone with Android Auto that supports wireless projection, **paired with the head unit over Bluetooth** in the usual way.
2. A **head-unit identity** file that DiPlay cannot supply. See [the identity](#the-head-unit-identity).
3. The Wi-Fi mode you already use for wireless CarPlay (Wi-Fi Direct, local-only hotspot, car hotspot or Existing Wi-Fi / Same LAN). Android Auto creates its network the same way and announces it to the phone.
4. The permissions wireless CarPlay needs: nearby devices (Bluetooth) and, depending on the Android version, nearby Wi-Fi devices or location.

Android Auto and CarPlay cannot run at the same time, because they share Bluetooth and the Wi-Fi network. While a CarPlay session runs, a phone that connects is ignored and the status says so.

## Turn it on

1. Open **Settings → Advanced → Android Auto (experimental)**.
2. Import the head-unit identity (below).
3. Turn on **Android Auto from an Android phone**. DiPlay asks for any missing permission and starts listening in the background. A notification shows its state.
4. Connect the phone to the car's Bluetooth. The phone should offer wireless Android Auto. Accept it. DiPlay creates the Wi-Fi network, tells the phone how to join it, and opens the projection screen when the phone is ready.

Turning the setting on or off takes effect at once and does not interrupt a CarPlay session. Leaving the projection screen with Back keeps the phone connected; open it again from the notification. **Disconnect** in the notification ends the projection.

## The head-unit identity

During the connection the phone asks the head unit to prove its identity with a TLS client certificate. The phone only accepts a certificate and key that it recognises as a head unit's.

**DiPlay does not include one, and cannot create one that a phone accepts.** This repository's public-tree check rejects certificates and private keys, and the build omits them, so the identity is provided by you at run time.

1. Make a file named `headunit.pem` containing, in PEM form, the certificate (or chain, leaf first) and its **RSA private key**, either `BEGIN PRIVATE KEY` (PKCS#8) or `BEGIN RSA PRIVATE KEY` (PKCS#1). Encrypted keys are not supported.
2. Copy it to the folder named in Settings. On most head units that is `Android/data/com.shihab.diplay/files/android-auto/headunit.pem` (the debug build adds a `.hudtest` suffix to the package name). DiPlay shows the exact path on the card.
3. Tap **Head-unit identity** in the card. DiPlay checks the file and stores a private copy that is excluded from backups. The status changes to *Ready*.

Your original file stays where you put it; delete it if you do not want a second copy. DiPlay checks that the key matches the certificate and shows an expired certificate as such, but it cannot tell whether a phone will accept the identity. If the TLS handshake fails, the log line `TLS handshake failed` appears under the tag `DiPlay-AndroidAuto`.

## What works

| Area | Behaviour |
| --- | --- |
| Connection | Bluetooth service `4de17a00-52cb-11e6-bdf4-0800200c9a66` → Wi-Fi network → TCP port 5288 → TLS 1.2 → service discovery |
| Video | H.264 from the phone, decoded with MediaCodec. 800×480, 1280×720 or 1920×1080, 30 or 60 fps (the CarPlay frame-rate setting). The size is the smallest that fills the screen. Other aspect ratios use margins that DiPlay crops, so the picture fills the display without stretching |
| Audio | Media 48 kHz stereo, navigation guidance 16 kHz mono, system sounds 16 kHz mono, each through its own audio stream |
| Microphone | The car microphone for voice assistant and calls, 16 kHz mono, only while the phone asks for it |
| Input | Multi-touch, plus phone, media, D-pad and search keys. Back and Home stay with the head unit so you can always leave the projection screen |
| Sensors | Night mode follows the Android UI mode. Driving status is always sent as restricted |

### Driving restrictions

DiPlay does not yet read the car's speed or parking brake for Android Auto, so it always reports a restricted driving state (no keyboard input, limited message length). This is the safe default: the phone applies its in-motion rules even when the car is parked.

## Not supported yet

- Wired (USB) Android Auto.
- The Bluetooth pairing channel. The phone must already be paired with the head unit.
- Navigation turn-by-turn data to the BYD cluster or HUD, media metadata, phone status and notifications.
- CarPlay's audio routing settings, music buffer and call echo cancellation. Android Auto audio uses the default Android routing.
- Starting the projection screen when Android blocks background activity starts. Use the notification then.

## Known risks

These are the places most likely to need changes once a real phone is available:

- **Waiting for the Wi-Fi network.** DiPlay creates the network after the phone opens the Bluetooth service, because creating a Wi-Fi Direct group needs the other projection to be idle. A Wi-Fi Direct group can take several seconds, and nothing is known about how long a phone waits on the Bluetooth link before it gives up. Existing Wi-Fi / Same LAN is the fastest mode.
- **The phone must offer wireless Android Auto at all.** Phones decide this from the Bluetooth profiles the car supports. DiPlay only provides the Android Auto service, not the hands-free or audio profiles.
- **The protocol was written from published definitions, not from a captured session.** Message ids and field numbers are taken from the reference, but ordering details (for example when a phone expects video focus) were implemented to be tolerant: DiPlay answers a focus request and also grants focus on its own once the screen is ready.
- **Nothing has run on a head unit.** Decoder, audio and microphone behaviour, the background start of the projection screen and the foreground service on each Android version are untested.

## Status messages

| Message | What to check |
| --- | --- |
| Import a head-unit identity first | Complete the identity steps above |
| Bluetooth is off / Allow nearby devices | Turn Bluetooth on; grant the permission in the app settings |
| The Wi-Fi network could not be started | The same causes as a wireless CarPlay hotspot failure. Check the Wi-Fi mode and its permissions |
| The phone did not join the car Wi-Fi network | The phone received the credentials but did not connect within a minute. Check that its Wi-Fi is on and that it can reach the network |
| The Android Auto version on the phone is not compatible | The phone reported a protocol version that DiPlay does not accept |
| The phone and DiPlay could not agree on the projection | The TLS handshake or the later messages failed. Capture the `DiPlay-AndroidAuto` log |

The log never contains Wi-Fi passwords or the identity.

## Design

The protocol layer lives in `shared/src/main/java/com/shilapi/xcertplay/androidauto/` and uses no Android classes, so plain JVM tests cover it:

- `ProtoWire`, `AapMessages`: a small protobuf reader and writer, and the message ids and layouts used.
- `AapFrame`: frame headers, splitting of large messages, per-frame encryption.
- `AapTls`: the TLS 1.2 client, and parsing of the PEM identity.
- `AapSession`: the control state machine and the video, audio, microphone, input and sensor channels. Platform work goes through `AapSessionHost`.
- `AapWirelessHandshake`: the Bluetooth message exchange that gives the phone the Wi-Fi details.

The Android parts are `AapBluetoothAcceptor`, `AapNetworkSetup` (which reuses the wireless CarPlay hotspot managers), `AapVideoDecoder`, `AapAudioPlayer`, `AapMicrophone`, `AndroidAutoConnection`, `AndroidAutoService` and `AndroidAutoActivity`. Settings, strings and the CarPlay gate live in `common` (`AndroidAutoSupport`).

The TLS client does not verify the phone's certificate. Android Auto does not authenticate the phone that way, and the peer is the phone itself on a network DiPlay created. This client must not be used for any other connection.

## Protocol reference and licence

The message ids and field numbers were read from the public protocol definitions in [OpenCarDev aasdk](https://github.com/opencardev/aasdk) (GPL-3.0). No source code was copied, and the certificate and key that project ships were not used or read. See [third-party notices](THIRD_PARTY_NOTICES.md).
