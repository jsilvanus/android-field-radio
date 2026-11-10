# android-field-radio

Configurable Android app that turns a phone into a portable radio broadcasting station: live microphone, a prerecorded sound library mixed into the same stream, AAC publish to a media server, optional captions later.

Status: first working vertical slice, never run on a phone. JVM modules are unit-tested; the RTMP publisher is tested against a real ffmpeg RTMP server; the debug APK builds. Not tested on a device: microphone capture, AAC encoder, library decoding, foreground service. Plan: [docs/plan.md](docs/plan.md). Licence: EUPL-1.2.

- `core-audio`: float PCM mixer, soft limiter, mic/library transition policy (stop, fade, duck, keep), resampler.
- `core-profile`: event profile model, validation (stream name `^[a-z0-9_-]{1,32}$`, STT engine setting, whisper disabled until 2.0), JSON import/export without credentials.
- `app`: Android app (Compose): foreground service, mic capture, library decoder, AAC encoder, mixer thread, operator UI. Direct RTMP only until the aidos SSH tunnel module is available.
- `core-publish`: seam to the aidos SSH tunnel module (`TunnelTransport`), `Route`, `Publisher` interface, `BroadcastSession` reconnect logic.

```
gradle test                 # JVM unit tests (Gradle 8.x, JDK 21; the RTMP test needs ffmpeg and skips without it)
gradle :app:assembleDebug   # needs ANDROID_HOME or local.properties sdk.dir (platform 34, build-tools 34)
```

Nothing here contains church-specific data, hosts or tokens.
