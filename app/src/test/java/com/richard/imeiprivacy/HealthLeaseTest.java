package com.richard.imeiprivacy;

import org.junit.Test;
import static org.junit.Assert.*;

public class HealthLeaseTest {
    private static final String FIRST = "0123456789abcdef0123456789abcdef";
    private static final String SECOND = "fedcba9876543210fedcba9876543210";

    @Test public void neverTestedAndInvalidChallengesCannotAllowNetwork() {
        HealthLease lease = new HealthLease();
        assertFalse(lease.permits(0, ""));
        lease.begin("invalid");
        lease.passed(100);
        assertFalse(lease.permits(100, "invalid"));
        lease.begin(null);
        lease.passed(101);
        assertFalse(lease.permits(101, null));
    }

    @Test public void successfulCheckOnlyAllowsAShortMatchingLease() {
        HealthLease lease = new HealthLease();
        lease.begin(FIRST);
        lease.passed(100);
        assertTrue(lease.permits(100, FIRST));
        assertTrue(lease.permits(115, FIRST));
        assertFalse(lease.permits(116, FIRST));
        assertFalse(lease.permits(100, SECOND));
        assertFalse(lease.permits(99, FIRST));
    }

    @Test public void failureIsImmediateAndLatchedForTheSameChallenge() {
        HealthLease lease = new HealthLease();
        lease.begin(FIRST);
        lease.passed(100);
        lease.failed("IDENTIFIER_NOT_MASKED");
        assertFalse(lease.permits(101, FIRST));
        lease.begin(FIRST);
        lease.passed(102);
        assertFalse(lease.permits(102, FIRST));
        assertEquals("FAIL", lease.state(102, FIRST));
    }

    @Test public void explicitRecheckChallengeCanRecoverButCannotReuseOldSuccess() {
        HealthLease lease = new HealthLease();
        lease.begin(FIRST);
        lease.failed("CHECK_ERROR");
        lease.begin(SECOND);
        assertFalse(lease.permits(100, SECOND));
        lease.passed(101);
        assertTrue(lease.permits(101, SECOND));
        assertFalse(lease.permits(101, FIRST));
    }

    @Test public void completedChecksRenewLeaseButBackgroundLivenessAloneDoesNot() {
        HealthLease lease = new HealthLease();
        lease.begin(FIRST);
        lease.passed(100);
        lease.begin(FIRST);
        assertFalse(lease.permits(116, FIRST));
        lease.passed(117);
        assertTrue(lease.permits(130, FIRST));
    }

    @Test public void errorMessagesCannotLeakIdentifiersIntoReports() {
        HealthLease lease = new HealthLease();
        lease.failed("an exception containing a private value");
        assertEquals("CHECK_ERROR", lease.reason());
        lease.failed("358240051234565");
        assertEquals("CHECK_ERROR", lease.reason());
        lease.failed("SAMPLE_CHECK_FAILED");
        assertEquals("SAMPLE_CHECK_FAILED", lease.reason());
    }

    @Test public void processIdentityHandlesSpacesAndParenthesesInComm() {
        StringBuilder stat = new StringBuilder("123 (some (process) name) S");
        for (int field = 4; field <= 21; field++) stat.append(" 0");
        stat.append(" 987654321 0 0");
        assertEquals("987654321", HealthLease.processStartTicks(stat.toString()));
    }

    @Test(expected = IllegalArgumentException.class) public void malformedProcessIdentityIsRejected() {
        HealthLease.processStartTicks("123 broken");
    }
}
