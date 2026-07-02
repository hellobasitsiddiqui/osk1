package io.openskeleton.backend.version;

import static org.assertj.core.api.Assertions.assertThat;

import io.openskeleton.backend.version.VersionController.Version;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

/**
 * Unit tests for {@link VersionController}'s value-resolution branches (OSK-100).
 *
 * <p>These construct the controller directly (via its package-private constructor)
 * so every source/fallback path — GIT_SHA env, build-info fallback, the "unknown"
 * sentinel, present/blank revision, present/absent build time — is exercised without
 * a Spring context. The HTTP contract (path, JSON, public access) is covered
 * separately by {@link VersionControllerWebTest}.
 */
class VersionControllerTest {

    /** Helper: build a {@link BuildProperties} with an optional time and git SHA. */
    private static BuildProperties buildInfo(String epochMillisTime, String gitSha) {
        Properties p = new Properties();
        p.setProperty("group", "io.openskeleton");
        p.setProperty("artifact", "backend");
        p.setProperty("version", "0.0.1-SNAPSHOT");
        if (epochMillisTime != null) {
            p.setProperty("time", epochMillisTime);
        }
        if (gitSha != null) {
            p.setProperty("git.sha", gitSha);
        }
        return new BuildProperties(p);
    }

    /** GIT_SHA env is the authoritative source and wins over everything else. */
    @Test
    void usesGitShaEnvWhenPresent() {
        BuildProperties bi = buildInfo("1751414400000", "buildinfosha");
        VersionController controller = new VersionController("abc1234", "rev-7", bi);

        Version v = controller.version();

        assertThat(v.sha()).isEqualTo("abc1234");
        assertThat(v.revision()).isEqualTo("rev-7");
        assertThat(v.buildTime()).isEqualTo(bi.getTime().toString());
    }

    /** With no GIT_SHA env, fall back to the SHA recorded in build-info. */
    @Test
    void fallsBackToBuildInfoShaWhenEnvBlank() {
        VersionController controller = new VersionController("", "", buildInfo(null, "buildinfosha"));

        Version v = controller.version();

        assertThat(v.sha()).isEqualTo("buildinfosha");
        // build-info present but no time set → buildTime is null.
        assertThat(v.buildTime()).isNull();
        // blank revision collapses to null.
        assertThat(v.revision()).isNull();
    }

    /** No env SHA and no build-info at all → the safe "unknown" sentinel, never a hardcode. */
    @Test
    void reportsUnknownWhenNoShaAnywhere() {
        VersionController controller = new VersionController(null, null, null);

        Version v = controller.version();

        assertThat(v.sha()).isEqualTo("unknown");
        assertThat(v.buildTime()).isNull();
        assertThat(v.revision()).isNull();
    }

    /** build-info present but its git.sha entry is blank → still falls through to "unknown". */
    @Test
    void reportsUnknownWhenBuildInfoShaBlank() {
        VersionController controller = new VersionController(null, null, buildInfo("1751414400000", "  "));

        Version v = controller.version();

        assertThat(v.sha()).isEqualTo("unknown");
        // time is still surfaced from build-info even when the SHA is missing.
        assertThat(v.buildTime()).isNotNull();
    }

    /** A whitespace-only revision is treated as absent (null), not echoed. */
    @Test
    void blankRevisionBecomesNull() {
        VersionController controller = new VersionController("abc1234", "   ", null);

        Version v = controller.version();

        assertThat(v.revision()).isNull();
    }
}
