package com.telequick.sdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ClientTest {

    @Test
    public void methodIdConstantsAreStable() {
        assertEquals(1430677891L, MethodID.ORIGINATE);
        assertEquals(721069100L, MethodID.ORIGINATE_BULK);
        assertEquals(3834253405L, MethodID.TERMINATE);
        assertEquals(959835745L, MethodID.STREAM_EVENTS);
        assertEquals(2991054320L, MethodID.AUDIO_FRAME);
    }

    @Test
    public void teleQuickClientReadsTenantIdFromCredentials(@TempDir Path tmp) throws IOException {
        Path credsPath = tmp.resolve("creds.json");
        Files.writeString(credsPath,
                "{\"tenant_id\": \"tenant-A\", \"private_key\": \"mock-key\", \"private_key_id\": \"kid\"}");

        TeleQuickClient client = new TeleQuickClient("quic://127.0.0.1:9090", credsPath.toString());
        assertNotNull(client);
        assertNull(client.onAudioFrame);
        assertNull(client.onCallEvent);
    }

    @Test
    public void teleQuickClientFallsBackWhenCredentialsMissing() {
        TeleQuickClient client = new TeleQuickClient("quic://127.0.0.1:9090", "/nonexistent/creds.json");
        assertNotNull(client);
    }
}
