# TeleQuick Java SDK The official Java SDK for the TeleQuick telephony platform. It includes generated native Protobuf/gRPC bindings and a high-level `TeleQuickClient` wrapper for simplified JWT authentication and media streaming. ## Installation This SDK is managed natively via Gradle. Simply run a build from the command line: ```bash
gradle build
``` ## Quick Start The client automatically loads your tenant credentials, signs requests via zero-trust JWTs, and handles the gRPC multiplexing. Ensure the `TELEQUICK_CREDENTIALS` environment variable is set to the path of your `credentials.json` file. ```java
import com.telequick.sdk.TeleQuickClient;
import com.telequick.sdk.TeleQuickAudioStream; public class Main { public static void main(String[] args) throws Exception { // 1. Initialize Client (Automatically reads TELEQUICK_CREDENTIALS) TeleQuickClient client = new TeleQuickClient("pbx.telequick.com:443"); // 2. Fetch Active Calls on a Trunk var calls = client.getIncomingCalls("trunk_123"); // 3. Connect to a WebSockets Audio Stream (AI Media Pump) TeleQuickAudioStream audioStream = new TeleQuickAudioStream("wss://pbx.telequick.com/media/session_456"); audioStream.onAudio(bytes -> { // Send PCM audio bytes to your AI LLM model }); }
}
```
