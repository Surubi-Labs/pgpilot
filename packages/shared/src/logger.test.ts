import { describe, expect, it } from "vitest";

import { createLogger } from "./logger.js";

describe("createLogger", () => {
  it("returns a pino-compatible logger with service bindings", () => {
    const logger = createLogger({ service: "api", env: "production" });

    expect(typeof logger.info).toBe("function");
    expect(typeof logger.error).toBe("function");
    expect(typeof logger.child).toBe("function");

    // pino attaches bindings through the `bindings()` method
    const bindings = logger.bindings();
    expect(bindings.service).toBe("api");
    expect(bindings.env).toBe("production");
  });

  it("respects an explicit level", () => {
    const logger = createLogger({ service: "api", env: "production", level: "warn" });
    expect(logger.level).toBe("warn");
  });

  it("defaults to info level when none is provided", () => {
    const logger = createLogger({ service: "api", env: "production" });
    expect(logger.level).toBe("info");
  });

  it("merges base fields into bindings", () => {
    const logger = createLogger({
      service: "orchestrator",
      env: "production",
      base: { region: "eu-central" },
    });

    expect(logger.bindings().region).toBe("eu-central");
  });
});
