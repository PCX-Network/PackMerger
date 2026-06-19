package sh.pcx.packmerger.merge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonMergerTest {

    @Test
    void parseJson_rejectsNonObject() {
        assertNull(JsonMerger.parseJson("[1, 2, 3]"));
        assertNull(JsonMerger.parseJson("\"string\""));
        assertNull(JsonMerger.parseJson("not json"));
    }

    @Test
    void deepMergeObjects_nonConflictingKeys() {
        JsonObject high = JsonMerger.parseJson("{ \"a\": 1 }");
        JsonObject low = JsonMerger.parseJson("{ \"b\": 2 }");
        JsonObject merged = JsonMerger.deepMergeObjects(high, low);
        assertEquals(1, merged.get("a").getAsInt());
        assertEquals(2, merged.get("b").getAsInt());
    }

    @Test
    void deepMergeObjects_primitiveConflict_highWins() {
        JsonObject high = JsonMerger.parseJson("{ \"a\": \"high\" }");
        JsonObject low = JsonMerger.parseJson("{ \"a\": \"low\" }");
        assertEquals("high", JsonMerger.deepMergeObjects(high, low).get("a").getAsString());
    }

    @Test
    void deepMergeObjects_nestedObjectMerges() {
        JsonObject high = JsonMerger.parseJson("{ \"n\": { \"x\": 1 } }");
        JsonObject low = JsonMerger.parseJson("{ \"n\": { \"y\": 2 } }");
        JsonObject merged = JsonMerger.deepMergeObjects(high, low);
        assertEquals(1, merged.getAsJsonObject("n").get("x").getAsInt());
        assertEquals(2, merged.getAsJsonObject("n").get("y").getAsInt());
    }

    @Test
    void concatArraysWithDedup_noIdentity_concatenates() {
        JsonArray high = JsonMerger.parseJson("{ \"a\": [1, 2] }").getAsJsonArray("a");
        JsonArray low = JsonMerger.parseJson("{ \"a\": [3, 4] }").getAsJsonArray("a");
        JsonArray merged = JsonMerger.concatArraysWithDedup(high, low, null);
        assertEquals(4, merged.size());
        assertEquals(1, merged.get(0).getAsInt());
        assertEquals(4, merged.get(3).getAsInt());
    }

    @Test
    void concatArraysWithDedup_identity_dropsLowDuplicates() {
        JsonArray high = JsonMerger.parseJson("{ \"a\": [\"x\", \"y\"] }").getAsJsonArray("a");
        JsonArray low = JsonMerger.parseJson("{ \"a\": [\"y\", \"z\"] }").getAsJsonArray("a");
        JsonArray merged = JsonMerger.concatArraysWithDedup(
                high, low, e -> e.getAsString());
        assertEquals(3, merged.size());
        assertEquals("x", merged.get(0).getAsString());
        assertEquals("y", merged.get(1).getAsString());
        assertEquals("z", merged.get(2).getAsString());
    }
}
