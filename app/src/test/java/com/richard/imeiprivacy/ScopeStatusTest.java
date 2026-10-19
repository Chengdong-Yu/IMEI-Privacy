package com.richard.imeiprivacy;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScopeStatusTest {
    private Map<String, String> fixture() {
        Map<String, String> values = new HashMap<>();
        values.put("scope_state", "READY");
        values.put("scope_reason", "CONFIG_READ");
        values.put("scope_finddevice", "1");
        values.put("scope_phone", "1");
        values.put("scope_system", "1");
        values.put("scope_user", "0");
        values.put("scope_checked_uptime", "100");
        return values;
    }
    @Test public void configuredAndEnabledNeedsAllThreeTargets() {
        ScopeStatus ready = ScopeStatus.configured(true, true, true, true, true);
        assertEquals("READY", ready.state);
        assertFalse(ready.requiresAction());
        assertEquals("MISSING", ScopeStatus.configured(true, true, false, true, true).state);
        assertEquals("MISSING", ScopeStatus.configured(true, true, true, false, true).state);
        assertEquals("MISSING", ScopeStatus.configured(true, true, true, true, false).state);
    }
    @Test public void newModuleMustBeRegisteredIndependentlyOfOldPackage() {
        ScopeStatus missing = ScopeStatus.configured(false, true, true, true, true);
        assertEquals("MODULE_NOT_FOUND", missing.state);
        assertTrue(missing.requiresAction());
    }
    @Test public void disabledModuleMustPromptEvenWhenTargetsSelected() {
        ScopeStatus disabled = ScopeStatus.configured(true, false, true, true, true);
        assertEquals("MODULE_DISABLED", disabled.state);
        assertTrue(disabled.requiresAction());
    }
    @Test public void missingTargetNamesAreExplicit() {
        ScopeStatus status = ScopeStatus.configured(true, true, false, true, false);
        assertTrue(status.missingLabels().contains("查找设备"));
        assertTrue(status.missingLabels().contains("系统框架"));
        assertFalse(status.missingLabels().contains("电话服务"));
    }
    @Test public void unknownDoesNotAccuseTheUserOfMissingScope() {
        assertFalse(ScopeStatus.unknown("UNSUPPORTED_SCHEMA").requiresAction());
        assertEquals("UNKNOWN", ScopeStatus.parse(new HashMap<>(), 100, 0).state);
    }
    @Test public void completeSnapshotIsReadyButNotARuntimeProof() {
        assertEquals("READY", ScopeStatus.parse(fixture(), 100, 0).state);
    }
    @Test public void staleAndFutureSnapshotsCannotBeTrusted() {
        assertEquals("UNKNOWN", ScopeStatus.parse(fixture(), 111, 0).state);
        assertEquals("READY", ScopeStatus.parse(fixture(), 110, 0).state);
        assertEquals("UNKNOWN", ScopeStatus.parse(fixture(), 99, 0).state);
    }
    @Test public void anotherUserConfigurationDoesNotCount() {
        assertEquals("UNKNOWN", ScopeStatus.parse(fixture(), 100, 10).state);
        Map<String, String> values = fixture(); values.put("scope_user", "10");
        assertEquals("READY", ScopeStatus.parse(values, 100, 10).state);
    }
    @Test public void readyClaimWithMissingTargetsIsUnknown() {
        Map<String, String> values = fixture(); values.put("scope_finddevice", "0");
        assertEquals("UNKNOWN", ScopeStatus.parse(values, 100, 0).state);
        values.put("scope_state", "MISSING");
        assertTrue(ScopeStatus.parse(values, 100, 0).requiresAction());
    }
    @Test public void malformedFlagsAndUnknownStateAreNotAccepted() {
        Map<String, String> values = fixture(); values.remove("scope_phone");
        assertEquals("UNKNOWN", ScopeStatus.parse(values, 100, 0).state);
        values = fixture(); values.put("scope_system", "yes");
        assertEquals("UNKNOWN", ScopeStatus.parse(values, 100, 0).state);
        values = fixture(); values.put("scope_state", "GREAT");
        assertEquals("UNKNOWN", ScopeStatus.parse(values, 100, 0).state);
    }
    @Test public void rawDetailsCannotBecomeDiagnosticReasons() {
        Map<String, String> values = fixture(); values.put("scope_state", "UNKNOWN");
        values.put("scope_reason", "123456789012345");
        assertEquals("READ_FAILED", ScopeStatus.parse(values, 100, 0).reason);
        values.put("scope_reason", "DATABASE_UNAVAILABLE");
        assertEquals("DATABASE_UNAVAILABLE", ScopeStatus.parse(values, 100, 0).reason);
    }
}
