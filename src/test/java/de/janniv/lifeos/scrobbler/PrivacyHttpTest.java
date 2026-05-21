package de.janniv.lifeos.scrobbler;

import de.janniv.lifeos.scrobbler.util.PrivacyHttp;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrivacyHttpTest {

    @Test
    void modeIsNoneWhenHostMissing() {
        PrivacyHttp http = new PrivacyHttp("", 0, "socks");
        assertEquals(PrivacyHttp.Mode.NONE, http.mode());
    }

    @Test
    void modeIsSocksWhenHostAndPortGivenAndModeIsSocks() {
        PrivacyHttp http = new PrivacyHttp("127.0.0.1", 9050, "socks");
        assertEquals(PrivacyHttp.Mode.SOCKS, http.mode());
    }

    @Test
    void modeIsHttpWhenExplicitlyHttp() {
        PrivacyHttp http = new PrivacyHttp("proxy.local", 3128, "http");
        assertEquals(PrivacyHttp.Mode.HTTP, http.mode());
    }

    @Test
    void formEncoderEscapesSpecialCharacters() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("artist", "Sigur Rós");
        form.put("title", "Hoppípolla & Friends");
        String body = PrivacyHttp.encodeForm(form);
        assertTrue(body.contains("artist=Sigur+R%C3%B3s") || body.contains("artist=Sigur%20R%C3%B3s"));
        assertTrue(body.contains("Hopp%C3%ADpolla"));
        assertTrue(body.contains("%26+Friends") || body.contains("%26%20Friends"));
    }
}
