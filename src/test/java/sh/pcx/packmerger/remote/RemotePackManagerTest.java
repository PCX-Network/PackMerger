package sh.pcx.packmerger.remote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RemotePackManagerTest {

    @Test
    void substituteEnv_noPlaceholders_passesThrough() {
        assertEquals("https://example.com/pack.zip",
                RemotePackManager.substituteEnv("https://example.com/pack.zip"));
    }

    @Test
    void substituteEnv_knownEnvVar_substitutes() {
        // PATH is virtually guaranteed to exist on any test host.
        String pathValue = System.getenv("PATH");
        assertNotNull(pathValue, "PATH should be set in this env");
        assertEquals("prefix-" + pathValue + "-suffix",
                RemotePackManager.substituteEnv("prefix-${PATH}-suffix"));
    }

    @Test
    void substituteEnv_unknownEnvVar_substitutesEmpty() {
        String result = RemotePackManager.substituteEnv("before-${__DEFINITELY_UNSET_VAR_12345}-after");
        assertEquals("before--after", result);
    }

    @Test
    void substituteEnv_null_returnsNull() {
        assertNull(RemotePackManager.substituteEnv(null));
    }

    @Test
    void fetchResult_successPredicates() {
        FetchResult fetched = new FetchResult("a", FetchResult.Status.FETCHED, "200");
        FetchResult notModified = new FetchResult("a", FetchResult.Status.NOT_MODIFIED, "304");
        FetchResult errorCache = new FetchResult("a", FetchResult.Status.ERROR_USING_CACHE, "timeout");
        FetchResult errorNoCache = new FetchResult("a", FetchResult.Status.ERROR_NO_CACHE, "timeout");
        FetchResult skipped = new FetchResult("a", FetchResult.Status.SKIPPED_BY_POLICY, "manual");

        assertTrue(fetched.isSuccess());
        assertTrue(notModified.isSuccess());
        assertFalse(errorCache.isSuccess());
        assertFalse(errorNoCache.isSuccess());
        assertFalse(skipped.isSuccess());

        assertTrue(errorCache.isFailure());
        assertTrue(errorNoCache.isFailure());
        assertFalse(fetched.isFailure());
        assertFalse(skipped.isFailure());
    }

    @Test
    void remoteSpec_refreshPolicyPredicates() {
        RemoteSpec startup = new RemoteSpec("a", "https://u", "on-startup", RemoteSpec.AuthSpec.NONE, false);
        RemoteSpec reload = new RemoteSpec("a", "https://u", "on-reload", RemoteSpec.AuthSpec.NONE, false);
        RemoteSpec manual = new RemoteSpec("a", "https://u", "manual", RemoteSpec.AuthSpec.NONE, false);

        assertTrue(startup.shouldFetchOnStartup());
        assertFalse(startup.shouldFetchOnReload());

        assertFalse(reload.shouldFetchOnStartup());
        assertTrue(reload.shouldFetchOnReload());

        assertFalse(manual.shouldFetchOnStartup());
        assertFalse(manual.shouldFetchOnReload());
    }

    @Test
    void authSpec_none() {
        assertTrue(RemoteSpec.AuthSpec.NONE.isNone());
        assertFalse(new RemoteSpec.AuthSpec("bearer", "t", null, null).isNone());
        assertTrue(new RemoteSpec.AuthSpec(null, null, null, null).isNone());
    }
}
