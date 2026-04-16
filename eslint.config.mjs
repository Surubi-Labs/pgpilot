/**
 * Root ESLint flat config for PgPilot.
 *
 * Uses the shared @pgpilot/eslint-config presets. Individual packages can
 * import a preset directly if they need a narrower ruleset. Keep rule
 * weakening inside this file (not per-package) so the policy stays central.
 */
import baseConfig from "@pgpilot/eslint-config/base";

export default [
  ...baseConfig,
  {
    files: ["**/*.{ts,tsx,js,mjs,cjs}"],
    ignores: [
      "**/dist/**",
      "**/build/**",
      "**/.next/**",
      "**/.turbo/**",
      "**/coverage/**",
      "**/storybook-static/**",
      "**/node_modules/**",
      "pnpm-lock.yaml",
    ],
  },
];
