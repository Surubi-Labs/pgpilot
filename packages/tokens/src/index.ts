/**
 * PgPilot design tokens.
 *
 * Editorial direction: restrained palette, confident typography pairing,
 * motion-compositor-friendly easings. Values are oklch() based for
 * perceptual uniformity and are mirrored in `./tokens.css` as CSS
 * custom properties for SCSS Modules and global styles.
 *
 * Keep this file and `./tokens.css` in sync: both are the source of truth
 * for downstream consumers (dashboard, landing, docs, Storybook).
 */

export const color = {
  // Neutral ramp (oklch L axis): used for surfaces, type, and borders.
  "neutral-0": "oklch(99% 0 0)",
  "neutral-50": "oklch(96% 0.003 255)",
  "neutral-100": "oklch(92% 0.005 255)",
  "neutral-200": "oklch(85% 0.007 255)",
  "neutral-300": "oklch(74% 0.009 255)",
  "neutral-400": "oklch(60% 0.011 255)",
  "neutral-500": "oklch(48% 0.013 255)",
  "neutral-600": "oklch(38% 0.015 255)",
  "neutral-700": "oklch(28% 0.017 255)",
  "neutral-800": "oklch(20% 0.017 255)",
  "neutral-900": "oklch(14% 0.015 255)",
  "neutral-1000": "oklch(8% 0.012 255)",

  // Accent ramp: cold, inked blue. Used for primary actions and focus rings.
  "accent-50": "oklch(97% 0.025 250)",
  "accent-100": "oklch(92% 0.05 250)",
  "accent-300": "oklch(78% 0.12 250)",
  "accent-500": "oklch(62% 0.18 250)",
  "accent-700": "oklch(48% 0.15 250)",
  "accent-900": "oklch(32% 0.11 250)",

  // Signal colors for status + alerts (tuned to match neutral/accent contrast).
  success: "oklch(68% 0.16 155)",
  warning: "oklch(78% 0.16 85)",
  danger: "oklch(60% 0.21 25)",
  info: "oklch(70% 0.14 230)",
} as const satisfies Record<string, string>;

export type ColorToken = keyof typeof color;

/**
 * Fluid typography scale. Base step is 1rem at a comfortable reading size
 * and scales with viewport via `clamp()` so display sizes breathe on desktop
 * without wrecking mobile line length.
 */
export const fontSize = {
  "text-xs": "clamp(0.75rem, 0.72rem + 0.15vw, 0.8125rem)",
  "text-sm": "clamp(0.875rem, 0.85rem + 0.2vw, 0.9375rem)",
  "text-base": "clamp(1rem, 0.95rem + 0.25vw, 1.0625rem)",
  "text-lg": "clamp(1.125rem, 1.05rem + 0.35vw, 1.25rem)",
  "text-xl": "clamp(1.375rem, 1.2rem + 0.7vw, 1.625rem)",
  "text-2xl": "clamp(1.75rem, 1.4rem + 1.4vw, 2.25rem)",
  "text-3xl": "clamp(2.25rem, 1.6rem + 2.6vw, 3.25rem)",
  "text-4xl": "clamp(3rem, 1.8rem + 4.8vw, 5rem)",
  "text-hero": "clamp(3.5rem, 1rem + 9vw, 8rem)",
} as const satisfies Record<string, string>;

export type FontSizeToken = keyof typeof fontSize;

export const fontFamily = {
  sans: "'Inter Variable', 'Inter', system-ui, sans-serif",
  serif: "'EB Garamond Variable', 'EB Garamond', Georgia, serif",
  mono: "'JetBrains Mono Variable', 'JetBrains Mono', ui-monospace, monospace",
} as const satisfies Record<string, string>;

export type FontFamilyToken = keyof typeof fontFamily;

export const fontWeight = {
  regular: "400",
  medium: "500",
  semibold: "600",
  bold: "700",
} as const satisfies Record<string, string>;

export type FontWeightToken = keyof typeof fontWeight;

export const lineHeight = {
  tight: "1.1",
  snug: "1.25",
  normal: "1.5",
  relaxed: "1.7",
} as const satisfies Record<string, string>;

export type LineHeightToken = keyof typeof lineHeight;

export const letterSpacing = {
  tighter: "-0.03em",
  tight: "-0.015em",
  normal: "0",
  wide: "0.02em",
  wider: "0.08em",
} as const satisfies Record<string, string>;

export type LetterSpacingToken = keyof typeof letterSpacing;

/**
 * Spacing scale. Uses rem-based steps on a modular 4px baseline so
 * editorial layouts can establish rhythm without magic numbers.
 */
export const space = {
  "space-0": "0",
  "space-1": "0.25rem",
  "space-2": "0.5rem",
  "space-3": "0.75rem",
  "space-4": "1rem",
  "space-5": "1.25rem",
  "space-6": "1.5rem",
  "space-8": "2rem",
  "space-10": "2.5rem",
  "space-12": "3rem",
  "space-16": "4rem",
  "space-20": "5rem",
  "space-24": "6rem",
  "space-32": "8rem",
  "space-section": "clamp(4rem, 3rem + 5vw, 10rem)",
} as const satisfies Record<string, string>;

export type SpaceToken = keyof typeof space;

export const radius = {
  "radius-none": "0",
  "radius-xs": "2px",
  "radius-sm": "4px",
  "radius-md": "8px",
  "radius-lg": "12px",
  "radius-xl": "20px",
  "radius-pill": "9999px",
} as const satisfies Record<string, string>;

export type RadiusToken = keyof typeof radius;

export const shadow = {
  "shadow-sm": "0 1px 2px 0 oklch(0% 0 0 / 6%)",
  "shadow-md": "0 4px 12px -2px oklch(0% 0 0 / 10%)",
  "shadow-lg": "0 12px 40px -4px oklch(0% 0 0 / 14%)",
  "shadow-focus": "0 0 0 3px oklch(62% 0.18 250 / 35%)",
} as const satisfies Record<string, string>;

export type ShadowToken = keyof typeof shadow;

/**
 * Motion tokens. Easings are compositor-friendly curves chosen for UI
 * interactions (out-expo for reveals, in-out-quart for layout settles).
 * Durations stay short by default — long durations are an opt-in signal.
 */
export const duration = {
  "duration-instant": "80ms",
  "duration-fast": "150ms",
  "duration-normal": "240ms",
  "duration-slow": "400ms",
  "duration-deliberate": "720ms",
} as const satisfies Record<string, string>;

export type DurationToken = keyof typeof duration;

export const easing = {
  "ease-standard": "cubic-bezier(0.4, 0, 0.2, 1)",
  "ease-out-expo": "cubic-bezier(0.16, 1, 0.3, 1)",
  "ease-in-out-quart": "cubic-bezier(0.76, 0, 0.24, 1)",
  "ease-spring": "cubic-bezier(0.34, 1.56, 0.64, 1)",
} as const satisfies Record<string, string>;

export type EasingToken = keyof typeof easing;

export const breakpoint = {
  "bp-xs": "320px",
  "bp-sm": "480px",
  "bp-md": "768px",
  "bp-lg": "1024px",
  "bp-xl": "1440px",
  "bp-2xl": "1920px",
} as const satisfies Record<string, string>;

export type BreakpointToken = keyof typeof breakpoint;

export const tokens = {
  color,
  fontSize,
  fontFamily,
  fontWeight,
  lineHeight,
  letterSpacing,
  space,
  radius,
  shadow,
  duration,
  easing,
  breakpoint,
} as const;

export type Tokens = typeof tokens;
