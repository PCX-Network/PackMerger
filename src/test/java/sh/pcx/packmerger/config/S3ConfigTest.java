package sh.pcx.packmerger.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class S3ConfigTest {

    @Test
    void privateAclPredicate() {
        assertTrue(base("private").isPrivateAcl());
        assertTrue(base("Private").isPrivateAcl());
        assertFalse(base("public-read").isPrivateAcl());
    }

    @Test
    void contentAddressedIsDefaultWhenStrategyUnrecognized() {
        assertTrue(withStrategy("content-addressed").isContentAddressed());
        assertTrue(withStrategy("anything-else").isContentAddressed(),
                "unknown strategy defaults to content-addressed");
        assertFalse(withStrategy("stable").isContentAddressed());
        assertFalse(withStrategy("Stable").isContentAddressed());
    }

    private ConfigManager.S3Config base(String acl) {
        return new ConfigManager.S3Config(
                "https://s3.amazonaws.com", "us-east-1", "bucket",
                "k", "s", "", "", acl, "public, max-age=60",
                "content-addressed", 24, 5);
    }

    private ConfigManager.S3Config withStrategy(String strategy) {
        return new ConfigManager.S3Config(
                "https://s3.amazonaws.com", "us-east-1", "bucket",
                "k", "s", "", "", "public-read", "public, max-age=60",
                strategy, 24, 5);
    }
}
