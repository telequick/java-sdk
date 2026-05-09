# TeleQuick Java SDK

The official Java SDK for TeleQuick. Targets JDK 17+; built with Gradle, also
publishable as a Maven artifact via `pom.xml`. Provides a high-level
`TeleQuickClient` wrapper for zero-trust JWT auth and media streaming.

## Build

```bash
gradle build
```

## Quick start

Set `TELEQUICK_CREDENTIALS` to the path of your `credentials.json`, then:

```java
import com.telequick.sdk.TeleQuickClient;
import com.telequick.sdk.TeleQuickAudioStream;

public class Main {
    public static void main(String[] args) throws Exception {
        // Reads TELEQUICK_CREDENTIALS automatically.
        TeleQuickClient client = new TeleQuickClient("pbx.telequick.com:443");

        // Inspect inbound calls on a trunk.
        var calls = client.getIncomingCalls("trunk_123");

        // Tap the realtime audio stream.
        TeleQuickAudioStream audio = new TeleQuickAudioStream(
            "wss://pbx.telequick.com/media/session_456");
        audio.onAudio(bytes -> {
            // Forward PCM to your LLM.
        });
    }
}
```

## Native core

The FFI core (`libtelequick_core_ffi.{so,dylib,dll}`) is loaded at runtime via
JNI. Set `TELEQUICK_LIB_PATH` if it isn't on the default loader path; see the
[`core-sdk`](https://github.com/telequick/core-sdk) repo for build details.
