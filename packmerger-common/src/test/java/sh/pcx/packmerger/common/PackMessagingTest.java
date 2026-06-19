package sh.pcx.packmerger.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PackMessagingTest {

    @Test
    void encodeDecode_roundTrip() {
        PackInfo in = new PackInfo("https://cdn.example.com/abc.zip", "deadbeef");
        PackInfo out = PackMessaging.decode(PackMessaging.encode(in));
        assertEquals(in, out);
        assertTrue(out.hasUrl());
        assertTrue(out.hasHash());
    }

    @Test
    void encodeDecode_nullHash_becomesNull() {
        PackInfo out = PackMessaging.decode(PackMessaging.encode(new PackInfo("https://x/y.zip", null)));
        assertEquals("https://x/y.zip", out.url());
        assertNull(out.sha1Hex());
        assertFalse(out.hasHash());
    }
}
