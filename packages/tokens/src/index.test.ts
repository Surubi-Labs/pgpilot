import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

import {
  color,
  duration,
  easing,
  fontFamily,
  fontSize,
  radius,
  shadow,
  space,
  tokens,
} from "./index.js";

const tokensCss = readFileSync(fileURLToPath(new URL("./tokens.css", import.meta.url)), "utf8");

describe("tokens — TS map", () => {
  it("exposes every top-level group", () => {
    expect(Object.keys(tokens).sort()).toEqual(
      [
        "breakpoint",
        "color",
        "duration",
        "easing",
        "fontFamily",
        "fontSize",
        "fontWeight",
        "letterSpacing",
        "lineHeight",
        "radius",
        "shadow",
        "space",
      ].sort(),
    );
  });

  it("color values are oklch()", () => {
    for (const value of Object.values(color)) {
      expect(value).toMatch(/^oklch\(/);
    }
  });

  it("font sizes use clamp() for fluid scaling", () => {
    for (const value of Object.values(fontSize)) {
      expect(value).toMatch(/clamp\(/);
    }
  });

  it("durations are in milliseconds", () => {
    for (const value of Object.values(duration)) {
      expect(value).toMatch(/^\d+ms$/);
    }
  });

  it("easings are cubic-bezier curves", () => {
    for (const value of Object.values(easing)) {
      expect(value).toMatch(/^cubic-bezier\(/);
    }
  });

  it("radius exposes all needed scales", () => {
    expect(Object.keys(radius)).toEqual(
      expect.arrayContaining(["radius-none", "radius-sm", "radius-md", "radius-lg", "radius-pill"]),
    );
  });

  it("shadow includes a focus ring token", () => {
    expect(shadow["shadow-focus"]).toBeDefined();
  });

  it("space includes fluid section spacing", () => {
    expect(space["space-section"]).toMatch(/clamp\(/);
  });

  it("font families include sans, serif and mono", () => {
    expect(fontFamily.sans).toContain("Inter");
    expect(fontFamily.serif).toContain("Garamond");
    expect(fontFamily.mono).toContain("JetBrains Mono");
  });
});

describe("tokens — CSS custom properties mirror the TS map", () => {
  const cssVars = collectCssVars(tokensCss);

  it("every color token has a matching --pg-* variable", () => {
    for (const key of Object.keys(color)) {
      expect(cssVars.has(`--pg-${key}`)).toBe(true);
    }
  });

  it("every font-size token has a matching --pg-* variable", () => {
    for (const key of Object.keys(fontSize)) {
      expect(cssVars.has(`--pg-${key}`)).toBe(true);
    }
  });

  it("every spacing token has a matching --pg-* variable", () => {
    for (const key of Object.keys(space)) {
      expect(cssVars.has(`--pg-${key}`)).toBe(true);
    }
  });

  it("every radius token has a matching --pg-* variable", () => {
    for (const key of Object.keys(radius)) {
      expect(cssVars.has(`--pg-${key}`)).toBe(true);
    }
  });

  it("every duration token has a matching --pg-* variable", () => {
    for (const key of Object.keys(duration)) {
      expect(cssVars.has(`--pg-${key}`)).toBe(true);
    }
  });

  it("honors prefers-reduced-motion by zeroing durations", () => {
    expect(tokensCss).toMatch(/@media\s*\(prefers-reduced-motion:\s*reduce\)/);
    const reducedBlock = tokensCss.slice(tokensCss.indexOf("prefers-reduced-motion"));
    expect(reducedBlock).toMatch(/--pg-duration-normal:\s*0ms/);
  });
});

function collectCssVars(css: string): Set<string> {
  const vars = new Set<string>();
  const pattern = /(--pg-[a-z0-9-]+)\s*:/gi;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(css)) !== null) {
    if (match[1]) vars.add(match[1]);
  }
  return vars;
}
