package sh.pcx.packmerger.merge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link PackValidator#resolveFormatDeclaration}, which the pack-format
 * guardrail uses to read both the legacy {@code pack_format} schema and the
 * 1.21.9+ (26.1) {@code min_format}/{@code max_format} schema.
 */
class PackValidatorFormatTest {

    private static JsonObject pack(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void legacy_packFormatOnly() {
        PackValidator.FormatDecl d = PackValidator.resolveFormatDeclaration(pack("{\"pack_format\":75}"));
        assertNotNull(d);
        assertEquals(75, d.declaredFormat());
        assertNull(d.supportedFormats());
    }

    @Test
    void legacy_withSupportedFormatsRange() {
        PackValidator.FormatDecl d = PackValidator.resolveFormatDeclaration(
                pack("{\"pack_format\":34,\"supported_formats\":[34,84]}"));
        assertEquals(34, d.declaredFormat());
        assertArrayEquals(new int[]{34, 84}, d.supportedFormats());
    }

    @Test
    void newSchema_minMaxIntegers() {
        // 26.1 packs use min_format/max_format instead of pack_format.
        PackValidator.FormatDecl d = PackValidator.resolveFormatDeclaration(
                pack("{\"min_format\":80,\"max_format\":88}"));
        assertEquals(88, d.declaredFormat());
        assertArrayEquals(new int[]{80, 88}, d.supportedFormats());
    }

    @Test
    void newSchema_majorMinorArrays_useMajor() {
        PackValidator.FormatDecl d = PackValidator.resolveFormatDeclaration(
                pack("{\"min_format\":[84,0],\"max_format\":[88,1]}"));
        assertEquals(88, d.declaredFormat());
        assertArrayEquals(new int[]{84, 88}, d.supportedFormats());
    }

    @Test
    void noRecognizableFormat_returnsNull() {
        assertNull(PackValidator.resolveFormatDeclaration(pack("{\"description\":\"x\"}")));
    }

    @Test
    void readFormatInt_handlesIntArrayAndJunk() {
        assertEquals(84, PackValidator.readFormatInt(JsonParser.parseString("84")));
        assertEquals(84, PackValidator.readFormatInt(JsonParser.parseString("[84,0]")));
        assertNull(PackValidator.readFormatInt(JsonParser.parseString("\"nope\"")));
        assertNull(PackValidator.readFormatInt(null));
    }

    @Test
    void endToEnd_newSchemaPackOn26_1_matches() {
        // A 26.1 pack (min/max 84) on a 26.1.2 server must classify as MATCH.
        PackValidator.FormatDecl d = PackValidator.resolveFormatDeclaration(
                pack("{\"min_format\":84,\"max_format\":84}"));
        assertEquals(PackFormatRegistry.Drift.MATCH,
                PackFormatRegistry.classify(d.declaredFormat(), d.supportedFormats(), "26.1.2"));
    }

    @Test
    void endToEnd_staleLegacyPackOn26_1_isMajorDrift() {
        // The issue #1 scenario: a merged pack still declaring an old pack_format
        // (no range covering 84) on a 26.1.2 server is now flagged, not silently OK.
        PackValidator.FormatDecl d = PackValidator.resolveFormatDeclaration(pack("{\"pack_format\":34}"));
        assertEquals(PackFormatRegistry.Drift.MAJOR,
                PackFormatRegistry.classify(d.declaredFormat(), d.supportedFormats(), "26.1.2"));
    }
}
