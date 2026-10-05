package com.company.lms.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window attempt limiter for authentication endpoints.
 * Counters live in this JVM only: correct for a single application instance, not shared across replicas.
 */
@Service
public class ThrottleService {

  /**
   * blocksSource tells the caller whether the block came from the caller's own address ("ip") rather than
   * from the account they named ("id"). A credential check may still verify the password when only the
   * account budget is spent, so a third party cannot lock a real user out of their own account.
   */
  public record Decision(boolean allowed, long retryAfterSeconds, boolean blocksSource) {
    static final Decision OK = new Decision(true, 0, false);

    Decision(boolean allowed, long retryAfterSeconds) { this(allowed, retryAfterSeconds, false); }

    public String message() {
      long minutes = Math.max(1, Math.round(retryAfterSeconds / 60.0));
      return "Too many attempts. Please try again in " + minutes + " minute" + (minutes == 1 ? "" : "s") + ".";
    }
  }

  /** escalates=false keeps a repeat offence at the same penalty: the login lockout is deliberately fixed. */
  private record Limit(String scope, int attempts, Duration window, boolean escalates) {
    Limit(String scope, int attempts, Duration window) { this(scope, attempts, window, true); }
  }

  /** A counter key and the rule it enforces. Several rules can share a key, so this is a list, never a map. */
  private record Target(String key, Limit limit) { }

  private final Map<String, List<Limit>> rules;

  private static final Duration REPEAT_OFFENCE_TTL = Duration.ofHours(24);
  private static final int MAX_ESCALATION = 3;
  private static final long SWEEP_INTERVAL = Duration.ofMinutes(5).toMillis();
  private static final int MAX_KEYS = 50_000;

  private static final class State {
    final Deque<Long> attempts = new ArrayDeque<>();
    int strikes;
    long lastStrikeAt;
  }

  private final Map<String, State> states = new ConcurrentHashMap<>();
  private volatile long lastSweepAt = System.currentTimeMillis();
  private final boolean trustForwardedFor;

  public ThrottleService(@Value("${app.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor,
      @Value("${app.rate-limit.login-attempts:5}") int loginAttempts,
      @Value("${app.rate-limit.login-ip-attempts:20}") int loginIpAttempts) {
    this.trustForwardedFor = trustForwardedFor;
    this.rules = Map.ofEntries(
        // Login penalties are deliberately fixed at the configured window: an escalating lockout punishes the
        // account owner far harder than the guesser, because the owner is the one who keeps retrying.
        Map.entry("login", List.of(new Limit("id", loginAttempts, Duration.ofMinutes(5), false),
            new Limit("ip", loginIpAttempts, Duration.ofMinutes(15), false))),
        Map.entry("otp-request", List.of(new Limit("id", 5, Duration.ofMinutes(10), false),
            new Limit("id", 15, Duration.ofHours(1), false), new Limit("ip", 30, Duration.ofHours(1)))),
        Map.entry("otp-verify", List.of(new Limit("id", 15, Duration.ofHours(1), false), new Limit("ip", 20, Duration.ofHours(1)))),
        Map.entry("password-complete", List.of(new Limit("id", 10, Duration.ofHours(1)), new Limit("ip", 20, Duration.ofHours(1)))),
        Map.entry("account-setup", List.of(new Limit("ip", 15, Duration.ofHours(1)))),
        Map.entry("change-password", List.of(new Limit("id", 8, Duration.ofMinutes(15)), new Limit("ip", 20, Duration.ofMinutes(15)))));
  }

  public Decision check(String action, String identifier, String ip) {
    List<Limit> limits = rules.get(action);
    if (limits == null) return Decision.OK;
    long now = System.currentTimeMillis();
    sweepIfNeeded(now);

    List<Target> targets = new ArrayList<>();
    for (Limit limit : limits) {
      String value = "ip".equals(limit.scope()) ? ip : identifier;
      if (value != null && !value.isBlank()) targets.add(new Target(key(action, limit, value), limit));
    }
    if (targets.isEmpty()) return Decision.OK;

    long retryAfter = 0;
    boolean blocksSource = false;
    for (Target target : targets) {
      long blockedFor = blockedFor(target, now);
      if (blockedFor > retryAfter) { retryAfter = blockedFor; blocksSource = "ip".equals(target.limit().scope()); }
    }

    for (Target target : targets) {
      State state = stateFor(target.key());
      if (state == null) continue;
      synchronized (state) {
        if (retryAfter > 0) {
          if (now - state.lastStrikeAt > REPEAT_OFFENCE_TTL.toMillis()) state.strikes = 0;
          state.strikes = Math.min(state.strikes + 1, MAX_ESCALATION);
          state.lastStrikeAt = now;
        } else {
          state.attempts.addLast(now);
          // Keep exactly enough timestamps to recognise the threshold: a fixed cap below the configured budget
          // would make that budget unreachable, i.e. silently unlimited.
          while (state.attempts.size() > target.limit().attempts()) state.attempts.removeFirst();
        }
      }
    }
    return retryAfter > 0 ? new Decision(false, retryAfter, blocksSource) : Decision.OK;
  }

  /** Clears counters once an attempt genuinely succeeds, so legitimate users are not punished for typos. */
  public void reset(String action, String identifier, String ip) {
    List<Limit> limits = rules.get(action);
    if (limits == null) return;
    for (Limit limit : limits) {
      String value = "ip".equals(limit.scope()) ? ip : identifier;
      if (value != null && !value.isBlank()) states.remove(key(action, limit, value));
    }
  }

  /** The window is part of the key: two rules over the same subject must not share one attempt log. */
  private static String key(String action, Limit limit, String value) {
    return action + '|' + limit.scope() + '|' + limit.window().toSeconds() + '|' + value;
  }

  private long blockedFor(Target target, long now) {
    State state = stateFor(target.key());
    if (state == null) return 0;
    synchronized (state) {
      long window = windowMillis(target.limit(), state, now);
      while (!state.attempts.isEmpty() && now - state.attempts.peekFirst() > window) state.attempts.removeFirst();
      if (state.attempts.size() < target.limit().attempts()) return 0;
      return Math.max(1, (window - (now - state.attempts.peekFirst())) / 1000);
    }
  }

  /** Returns null instead of creating a key once the table is full, so a sprayed key space cannot exhaust memory. */
  private State stateFor(String key) {
    State existing = states.get(key);
    if (existing != null) return existing;
    if (states.size() >= MAX_KEYS) return null;
    return states.computeIfAbsent(key, k -> new State());
  }

  private long windowMillis(Limit limit, State state, long now) {
    if (!limit.escalates()) return limit.window().toMillis();
    int multiplier = now - state.lastStrikeAt > REPEAT_OFFENCE_TTL.toMillis() ? 1 : 1 << state.strikes;
    return limit.window().toMillis() * Math.min(multiplier, 1 << MAX_ESCALATION);
  }

  private void sweepIfNeeded(long now) {
    if (now - lastSweepAt < SWEEP_INTERVAL && states.size() < MAX_KEYS) return;
    lastSweepAt = now;
    states.entrySet().removeIf(entry -> {
      State state = entry.getValue();
      synchronized (state) {
        boolean idle = state.attempts.isEmpty() || now - state.attempts.peekLast() > Duration.ofHours(2).toMillis();
        return idle && now - state.lastStrikeAt > REPEAT_OFFENCE_TTL.toMillis();
      }
    });
  }

  public String clientIp(HttpServletRequest request) {
    if (trustForwardedFor) {
      String forwarded = request.getHeader("X-Forwarded-For");
      if (forwarded != null && !forwarded.isBlank()) {
        String first = forwarded.split(",")[0].trim();
        if (!first.isEmpty()) return first;
      }
    }
    String remote = request.getRemoteAddr();
    return remote == null ? "unknown" : remote;
  }
}
