package com.richard.imeiprivacy;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModuleStatusTest {
    private String fixture() {
        return "root=1\nprotocol=1\nmodule_version=1.4.5\npending_enabled=1\nactive_enabled=1\n"
                + "boot_ready=1\nmodule_disabled=0\nguard_state=ALLOWED\nreason=ALL_CURRENT_PROCESSES_PASSED\n"
                + "checked_uptime=100\nnonce_match=1\nprocesses=2\nverified=2\n";
    }

    @Test public void validSnapshotShowsVerifiedState() {
        ModuleStatus status = ModuleStatus.parse(fixture(), 100);
        assertTrue(status.available);
        assertEquals("ALLOWED", status.state);
        assertFalse(status.needsReboot());
        assertTrue(status.canRecheck());
    }
    @Test public void requestedDisableDoesNotPretendProtectionAlreadyStopped() {
        ModuleStatus status = ModuleStatus.parse(fixture().replace("pending_enabled=1", "pending_enabled=0"), 100);
        assertEquals("ALLOWED", status.state);
        assertTrue(status.needsReboot());
        assertFalse(status.pendingEnabled);
        assertEquals(Boolean.TRUE, status.activeEnabled);
    }
    @Test public void disabledBootDoesNotRequireHealthPass() {
        ModuleStatus status = ModuleStatus.parse(fixture().replace("pending_enabled=1", "pending_enabled=0")
                .replace("active_enabled=1", "active_enabled=0").replace("checked_uptime=100", "checked_uptime=0"), 100);
        assertEquals("DISABLED", status.state);
        assertFalse(status.canRecheck());
        assertFalse(status.needsReboot());
    }
    @Test public void enableAfterDisabledBootRequiresReboot() {
        ModuleStatus status = ModuleStatus.parse(fixture().replace("active_enabled=1", "active_enabled=0"), 100);
        assertEquals("DISABLED", status.state);
        assertTrue(status.needsReboot());
    }
    @Test public void staleAndFutureRecordsAreNotPresentedAsAllowed() {
        assertEquals("UNKNOWN", ModuleStatus.parse(fixture(), 106).state);
        assertEquals("ALLOWED", ModuleStatus.parse(fixture(), 105).state);
        assertEquals("UNKNOWN", ModuleStatus.parse(fixture(), 99).state);
    }
    @Test public void nonceMismatchCannotAllow() {
        assertEquals("UNKNOWN", ModuleStatus.parse(fixture().replace("nonce_match=1", "nonce_match=0"), 100).state);
    }
    @Test public void unverifiedNewProcessCannotAllow() {
        assertEquals("UNKNOWN", ModuleStatus.parse(fixture().replace("processes=2", "processes=3"), 100).state);
        assertEquals("UNKNOWN", ModuleStatus.parse(fixture().replace("processes=2", "processes=0")
                .replace("verified=2", "verified=0"), 100).state);
    }
    @Test public void missingOrCorruptCountsCannotAllow() {
        assertEquals("UNKNOWN", ModuleStatus.parse(fixture().replace("verified=2\n", ""), 100).state);
        assertEquals("UNKNOWN", ModuleStatus.parse(fixture().replace("verified=2", "verified=-1"), 100).state);
    }
    @Test public void missingBootMarkerOrActiveFlagIsUnknown() {
        assertEquals("UNKNOWN", ModuleStatus.parse(fixture().replace("boot_ready=1", "boot_ready=0"), 100).state);
        assertEquals("UNKNOWN", ModuleStatus.parse(fixture().replace("active_enabled=1", "active_enabled=unknown"), 100).state);
    }
    @Test public void disabledModuleCannotClaimHealth() {
        ModuleStatus status = ModuleStatus.parse(fixture().replace("module_disabled=0", "module_disabled=1"), 100);
        assertEquals("UNKNOWN", status.state);
        assertFalse(status.canRecheck());
    }
    @Test public void duplicateProtocolOrMissingSettingsInvalidateSnapshot() {
        assertFalse(ModuleStatus.parse(fixture() + "protocol=1\n", 100).available);
        assertFalse(ModuleStatus.parse(fixture().replace("pending_enabled=1\n", ""), 100).available);
        assertFalse(ModuleStatus.parse(fixture().replace("module_disabled=0\n", ""), 100).available);
        assertFalse(ModuleStatus.parse(fixture() + "error=RECHECK_FAILED\n", 100).available);
    }
    @Test public void unknownAndIdentifierLikeTextNeverBecomesAReason() {
        assertEquals("STATUS_UNAVAILABLE", ModuleStatus.parse(fixture()
                .replace("ALL_CURRENT_PROCESSES_PASSED", "123456789012345"), 100).reason);
        assertEquals("UNKNOWN", ModuleStatus.parse(fixture().replace("guard_state=ALLOWED", "guard_state=GREAT"), 100).state);
        assertFalse(ModuleStatus.parse(null, 100).available);
    }

    @Test public void savedScopeAndRuntimeEvidenceAreIndependent() {
        String scope = "scope_state=MISSING\nscope_reason=CONFIG_READ\nscope_finddevice=0\nscope_phone=1\n"
                + "scope_system=1\nscope_user=0\nscope_checked_uptime=100\n";
        ModuleStatus status = ModuleStatus.parse(fixture() + scope, 100, 0);
        assertEquals("ALLOWED", status.state);
        assertTrue(status.scope.requiresAction());
        assertEquals("UNKNOWN", ModuleStatus.parse(fixture() + scope, 100, 10).scope.state);
    }

    @Test public void selectedScopeCannotTurnStaleHealthIntoPass() {
        String scope = "scope_state=READY\nscope_reason=CONFIG_READ\nscope_finddevice=1\nscope_phone=1\n"
                + "scope_system=1\nscope_user=0\nscope_checked_uptime=106\n";
        ModuleStatus status = ModuleStatus.parse(fixture() + scope, 106, 0);
        assertEquals("READY", status.scope.state);
        assertEquals("UNKNOWN", status.state);
    }
}
