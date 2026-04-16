package sh.pcx.packmerger.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProfileConfigTest {

    @Test
    void constructor_defensiveCopies() {
        List<String> priority = new java.util.ArrayList<>(List.of("a.zip", "b.zip"));
        Map<String, ConfigManager.ServerPackConfig> serverPacks = new java.util.HashMap<>();
        serverPacks.put("lobby", new ConfigManager.ServerPackConfig(
                List.of("lobby-ui.zip"), List.of("survival-ui.zip")));

        ProfileConfig profile = new ProfileConfig("halloween", priority, serverPacks);

        // Mutate inputs after construction — profile must not observe the mutations
        priority.add("surprise.zip");
        serverPacks.clear();

        assertEquals(List.of("a.zip", "b.zip"), profile.priority());
        assertEquals(1, profile.serverPacks().size());
    }

    @Test
    void priority_immutable() {
        ProfileConfig profile = new ProfileConfig("x",
                List.of("a.zip"),
                Map.of());
        assertThrows(UnsupportedOperationException.class, () -> profile.priority().add("b.zip"));
    }

    @Test
    void serverPacks_immutable() {
        ProfileConfig profile = new ProfileConfig("x",
                List.of(),
                Map.of("s", new ConfigManager.ServerPackConfig(List.of(), List.of())));
        assertThrows(UnsupportedOperationException.class,
                () -> profile.serverPacks().put("y", new ConfigManager.ServerPackConfig(List.of(), List.of())));
    }

    @Test
    void nameAccessorReturnsKey() {
        ProfileConfig profile = new ProfileConfig("halloween", List.of(), Map.of());
        assertEquals("halloween", profile.name());
    }
}
