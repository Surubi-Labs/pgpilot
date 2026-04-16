import { describe, expect, it } from "vitest";

/**
 * Sanity assertions that prove the CI pipeline is actually executing tests
 * and reading environment variables declared in turbo.json's globalEnv.
 * Surfaces regressions where tests compile but silently stop running.
 */
describe("ci-sanity", () => {
  it("runs under Vitest", () => {
    // Vitest sets VITEST=true when tests execute.
    expect(process.env.VITEST).toBe("true");
  });

  it("has access to Node >= 20", () => {
    const [majorStr] = process.versions.node.split(".");
    const major = Number.parseInt(majorStr ?? "0", 10);
    expect(Number.isFinite(major)).toBe(true);
    expect(major).toBeGreaterThanOrEqual(20);
  });

  it("confirms basic async works inside the test runner", async () => {
    const value = await Promise.resolve(42);
    expect(value).toBe(42);
  });
});
