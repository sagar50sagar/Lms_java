package com.company.lms.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The limiter is the only thing standing between an anonymous caller and unlimited password guesses. */
class ThrottleServiceTests {

  private final ThrottleService throttle = new ThrottleService(false, 10, 40);

  @Test
  @DisplayName("login allows ten attempts per account then blocks for five minutes")
  void loginBlocksAfterTen() {
    for (int attempt = 1; attempt <= 10; attempt++) {
      assertTrue(throttle.check("login", "a@b.com", "10.0.0.1").allowed(), "attempt " + attempt + " should pass");
    }
    ThrottleService.Decision blocked = throttle.check("login", "a@b.com", "10.0.0.1");
    assertFalse(blocked.allowed());
    assertTrue(blocked.retryAfterSeconds() > 0);
    assertTrue(blocked.retryAfterSeconds() <= 5 * 60, "the account lockout must never exceed its 5 minute window");
    assertFalse(blocked.blocksSource(), "an account budget says nothing about who is calling");
    // A different account on the same machine is unaffected until the per-IP budget is spent.
    assertTrue(throttle.check("login", "other@b.com", "10.0.0.2").allowed());
  }

  @Test
  @DisplayName("repeat login lockouts stay flat instead of escalating against the account owner")
  void lockoutDoesNotEscalate() {
    for (int attempt = 1; attempt <= 10; attempt++) throttle.check("login", "slow@b.com", "10.0.5.1");
    long first = throttle.check("login", "slow@b.com", "10.0.5.1").retryAfterSeconds();
    for (int retry = 0; retry < 40; retry++) throttle.check("login", "slow@b.com", "10.0.5.1");
    long after = throttle.check("login", "slow@b.com", "10.0.5.1").retryAfterSeconds();
    assertTrue(after <= 5 * 60, "hammering a blocked account must not grow the window past 5 minutes");
    assertTrue(after <= first + 1, "retry window should shrink, not compound");
  }

  @Test
  @DisplayName("a spent IP budget is reported as blocking the source, so login can refuse it outright")
  void ipBudgetBlocksSource() {
    for (int attempt = 1; attempt <= 40; attempt++) {
      throttle.check("login", "user" + attempt + "@b.com", "10.0.6.1");
    }
    ThrottleService.Decision blocked = throttle.check("login", "someone@b.com", "10.0.6.1");
    assertFalse(blocked.allowed());
    assertTrue(blocked.blocksSource(), "the caller's own address is exhausted and may be refused unverified");
    assertTrue(blocked.retryAfterSeconds() <= 15 * 60);
  }

  @Test
  @DisplayName("a budget larger than the old attempt-log cap is still reachable")
  void wideBudgetStillBlocks() {
    // Regression: the log used to be trimmed at 64 entries, so any configured budget above that could never be
    // met and the rule looked active while enforcing nothing at all.
    ThrottleService wide = new ThrottleService(false, 10, 200);
    for (int attempt = 1; attempt <= 200; attempt++) {
      assertTrue(wide.check("login", "wide" + attempt + "@b.com", "10.0.7.1").allowed(), "attempt " + attempt);
    }
    ThrottleService.Decision blocked = wide.check("login", "wide201@b.com", "10.0.7.1");
    assertFalse(blocked.allowed(), "the 201st request from one address must be refused");
    assertTrue(blocked.blocksSource());
  }

  @Test
  @DisplayName("a successful login clears the counters so typos are not punished")
  void successResets() {
    for (int attempt = 0; attempt < 10; attempt++) throttle.check("login", "reset@b.com", "10.0.1.1");
    assertFalse(throttle.check("login", "reset@b.com", "10.0.1.1").allowed());
    throttle.reset("login", "reset@b.com", "10.0.1.1");
    assertTrue(throttle.check("login", "reset@b.com", "10.0.1.1").allowed());
  }

  @Test
  @DisplayName("OTP requests are capped far tighter than logins")
  void otpRequestCapped() {
    for (int attempt = 1; attempt <= 5; attempt++) {
      assertTrue(throttle.check("otp-request", "otp@b.com", "10.0.2.1").allowed(), "request " + attempt);
    }
    assertFalse(throttle.check("otp-request", "otp@b.com", "10.0.2.1").allowed(), "sixth code in ten minutes must be refused");
  }

  @Test
  @DisplayName("account setup has no account identifier and still throttles by IP")
  void ipOnlyAction() {
    for (int attempt = 0; attempt < 15; attempt++) {
      assertTrue(throttle.check("account-setup", null, "10.0.3.1").allowed());
    }
    assertFalse(throttle.check("account-setup", null, "10.0.3.1").allowed());
  }

  @Test
  @DisplayName("unknown actions pass through and the retry message is human readable")
  void unknownActionPassesThrough() {
    assertTrue(throttle.check("not-a-protected-action", "x@y.z", "10.0.4.1").allowed());
    assertTrue(new ThrottleService.Decision(false, 120).message().contains("2 minutes"));
    assertTrue(new ThrottleService.Decision(false, 60).message().contains("1 minute."));
  }
}
