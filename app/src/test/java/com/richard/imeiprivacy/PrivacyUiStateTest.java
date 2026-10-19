package com.richard.imeiprivacy;

import org.junit.Test;
import static org.junit.Assert.*;

public class PrivacyUiStateTest {
    private String snapshot() {
        return "root=1\nprotocol=1\nmodule_version=1.4.6\npending_enabled=1\nactive_enabled=1\n"
                + "boot_ready=1\nmodule_disabled=0\nguard_state=ALLOWED\nreason=ALL_CURRENT_PROCESSES_PASSED\n"
                + "checked_uptime=100\nnonce_match=1\nprocesses=2\nverified=2\n";
    }
    private PrivacyUiState ui(String text, long now) {
        return new PrivacyUiState(ModuleStatus.parse(text, now), false, true, false, false, null, 0, true);
    }
    @Test public void initialScreenDoesNotClaimEnabledOrVerified() {
        PrivacyUiState state = new PrivacyUiState(null, false, false, false, false, null, 0, false);
        assertEquals("等待读取状态", state.statusTitle);
        assertEquals("未知", state.currentValue);
        assertEquals("未读取", state.pendingValue);
        assertFalse(state.switchEnabled);
        assertFalse(state.recheckEnabled);
        assertTrue(state.refreshEnabled);
    }
    @Test public void deniedRootDisablesAllRootOperationsEvenWithCachedStatus() {
        PrivacyUiState state = new PrivacyUiState(ModuleStatus.parse(snapshot(), 100), false, false, true, false, "ROOT_DENIED", 0, false);
        assertFalse(state.refreshEnabled);
        assertFalse(state.switchEnabled);
        assertFalse(state.recheckEnabled);
        assertFalse(state.rebootEnabled);
    }
    @Test public void busyStatePreventsDuplicateCommands() {
        PrivacyUiState state = new PrivacyUiState(ModuleStatus.parse(snapshot(), 100), true, true, false, false, null, 0, true);
        assertTrue(state.busy);
        assertFalse(state.refreshEnabled);
        assertFalse(state.switchEnabled);
        assertFalse(state.recheckEnabled);
        assertFalse(state.rebootEnabled);
        assertEquals("正在读取…", state.refreshLabel);
    }
    @Test public void verifiedStateKeepsWarningAboutCoverage() {
        PrivacyUiState state = ui(snapshot(), 100);
        assertEquals("自检通过，允许联网", state.statusTitle);
        assertTrue(state.statusSummary.contains("不代表"));
        assertEquals(PrivacyUiState.Tone.PRIMARY, state.statusTone);
        assertEquals("2 / 2", state.verifiedValue);
        assertTrue(state.switchEnabled);
        assertFalse(state.needsReboot);
    }
    @Test public void pendingDisableDoesNotPretendRuntimeProtectionAlreadyStopped() {
        PrivacyUiState state = ui(snapshot().replace("pending_enabled=1", "pending_enabled=0"), 100);
        assertTrue(state.needsReboot);
        assertEquals("开启", state.currentValue);
        assertEquals("关闭", state.pendingValue);
        assertFalse(state.pendingEnabled);
        assertEquals("自检通过，允许联网", state.statusTitle);
    }
    @Test public void expiredHealthNeverShowsPass() {
        PrivacyUiState state = ui(snapshot(), 106);
        assertEquals("状态尚未验证", state.statusTitle);
        assertTrue(state.statusSummary.contains("过期"));
        assertNotEquals(PrivacyUiState.Tone.PRIMARY, state.statusTone);
    }
    @Test public void blockedStateDoesNotSayAllHooksWork() {
        PrivacyUiState state = ui(snapshot().replace("guard_state=ALLOWED", "guard_state=BLOCKED"), 100);
        assertEquals("查找设备已断网", state.statusTitle);
        assertTrue(state.statusSummary.contains("不会当作已经通过"));
    }
    @Test public void disabledBootCannotRecheck() {
        PrivacyUiState state = ui(snapshot().replace("pending_enabled=1", "pending_enabled=0")
                .replace("active_enabled=1", "active_enabled=0"), 100);
        assertEquals("IMEI 伪装已关闭", state.statusTitle);
        assertFalse(state.recheckEnabled);
        assertFalse(state.needsReboot);
    }
    @Test public void unknownScopeIsNotAnAccusation() {
        PrivacyUiState state = ui(snapshot(), 100);
        assertEquals("作用域尚未确认", state.scopeTitle);
        assertTrue(state.scopeSummary.contains("不代表一定未勾选"));
        assertFalse(state.scopeNeedsAction);
    }
    @Test public void scopePromptListsOnlyMissingTargets() {
        String scope = "scope_state=MISSING\nscope_reason=CONFIG_READ\nscope_finddevice=0\nscope_phone=1\n"
                + "scope_system=0\nscope_user=0\nscope_checked_uptime=100\n";
        PrivacyUiState state = ui(snapshot() + scope, 100);
        assertTrue(state.scopeNeedsAction);
        assertTrue(state.scopeSummary.contains("查找设备"));
        assertTrue(state.scopeSummary.contains("系统框架"));
        assertFalse(state.scopeSummary.contains("电话服务"));
    }
    @Test public void selectedScopeDoesNotProveRuntimeHealth() {
        String scope = "scope_state=READY\nscope_reason=CONFIG_READ\nscope_finddevice=1\nscope_phone=1\n"
                + "scope_system=1\nscope_user=0\nscope_checked_uptime=106\n";
        PrivacyUiState state = ui(snapshot() + scope, 106);
        assertEquals("关键作用域已勾选", state.scopeTitle);
        assertEquals("状态尚未验证", state.statusTitle);
    }
    @Test public void legacyPackageWarningIsPreserved() {
        PrivacyUiState state = new PrivacyUiState(ModuleStatus.parse(snapshot(), 100), false, true, false, true, null, 0, true);
        assertTrue(state.scopeSummary.contains("com.dsh.imeiprivacy"));
        assertTrue(state.scopeSummary.contains("停用旧模块"));
    }
    @Test public void pausedRefreshAndNegativeAgeHaveAccurateLabels() {
        PrivacyUiState state = new PrivacyUiState(ModuleStatus.parse(snapshot(), 100), false, true, false, false, null, -3, false);
        assertTrue(state.snapshotLabel.contains("自动刷新已暂停"));
        assertTrue(state.snapshotLabel.contains("0 秒前读取"));
    }
    @Test public void confirmationsKeepFixedCommandsAndCautionFlags() {
        assertEquals("set-enabled 1", PrivacyConfirmation.ENABLE.command);
        assertEquals("set-enabled 0", PrivacyConfirmation.DISABLE.command);
        assertEquals("recheck", PrivacyConfirmation.RECHECK.command);
        assertEquals("reboot-confirmed", PrivacyConfirmation.REBOOT.command);
        assertTrue(PrivacyConfirmation.DISABLE.caution);
        assertTrue(PrivacyConfirmation.REBOOT.caution);
        assertNull(PrivacyConfirmation.SCOPE.command);
        assertNull(PrivacyConfirmation.MANAGER_HELP.command);
    }
    @Test public void presentationHasNoFullImeiOrMeidValues() {
        PrivacyUiState state = ui(snapshot(), 100);
        String text = state.statusTitle + state.statusSummary + state.scopeSummary + state.reason + state.settingSummary;
        assertFalse(text.contains("358240051234565"));
        assertFalse(text.contains("A1000000000001"));
    }
}
