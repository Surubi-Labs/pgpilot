/**
 * Stylelint config for PgPilot.
 *
 * SCSS Modules are the primary styling system in apps/dashboard, apps/landing,
 * and packages/ui. Rules enforce camelCase class names (for CSS Modules auto
 * import), --pg-* custom properties, and alphabetical property order.
 */
export default {
  extends: ["stylelint-config-standard-scss"],
  plugins: ["stylelint-order"],
  rules: {
    "order/properties-alphabetical-order": true,
    "selector-class-pattern": [
      "^[a-z][a-zA-Z0-9]+$",
      {
        message: "Expected class name to be camelCase (for CSS Modules).",
      },
    ],
    "scss/at-rule-no-unknown": true,
    "custom-property-pattern": [
      "^pg-[a-z0-9-]+$",
      { message: "Design-token custom properties must use the --pg- prefix." },
    ],
    "no-descending-specificity": null,
  },
  ignoreFiles: [
    "**/node_modules/**",
    "**/dist/**",
    "**/build/**",
    "**/.next/**",
    "**/.turbo/**",
    "**/storybook-static/**",
  ],
};
