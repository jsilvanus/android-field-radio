# android-field-radio

Configurable Android app that turns a phone into a portable radio broadcasting station: live microphone, a prerecorded sound library mixed into the same stream, AAC publish to a media server, optional captions later.

Status: planning plus the first pure-Kotlin modules. Plan: [docs/plan.md](docs/plan.md). Licence: EUPL-1.2.

- `core-audio`: float PCM mixer, soft limiter, mic/library transition policy (stop, fade, duck, keep), resampler.
- `core-profile`: event profile model, validation (stream name `^[a-z0-9_-]{1,32}$`, STT engine setting, whisper disabled until 2.0), JSON import/export without credentials.

```
gradle test      # JVM unit tests (Gradle 8.x, JDK 21)
```

Nothing here contains church-specific data, hosts or tokens.
