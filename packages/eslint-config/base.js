import js from "@eslint/js";
import prettierConfig from "eslint-config-prettier";
import importPlugin from "eslint-plugin-import";
import turboPlugin from "eslint-plugin-turbo";
import tseslint from "typescript-eslint";

/**
 * Base PgPilot ESLint flat config.
 *
 * Uses `tseslint.configs.recommended` (not `recommendedTypeChecked`) so this
 * preset works out of the box on any workspace package without requiring a
 * `project` / `projectService` setup. Packages that want type-aware linting
 * should opt in at their own root config:
 *
 *   import { baseConfig, typeCheckedConfig } from "@pgpilot/eslint-config/base";
 *   export default [...baseConfig, ...typeCheckedConfig({ tsconfigRootDir: import.meta.dirname })];
 */
export const baseConfig = [
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    plugins: {
      turbo: turboPlugin,
      import: importPlugin,
    },
    rules: {
      "turbo/no-undeclared-env-vars": "warn",
      "@typescript-eslint/no-unused-vars": [
        "error",
        {
          argsIgnorePattern: "^_",
          varsIgnorePattern: "^_",
          caughtErrorsIgnorePattern: "^_",
        },
      ],
      "@typescript-eslint/consistent-type-imports": [
        "error",
        { prefer: "type-imports", fixStyle: "separate-type-imports" },
      ],
      "import/order": [
        "warn",
        {
          groups: ["builtin", "external", "internal", "parent", "sibling", "index"],
          "newlines-between": "always",
          alphabetize: { order: "asc", caseInsensitive: true },
        },
      ],
      "no-console": ["warn", { allow: ["warn", "error"] }],
    },
  },
  {
    ignores: [
      "**/dist/**",
      "**/build/**",
      "**/.next/**",
      "**/.turbo/**",
      "**/coverage/**",
      "**/storybook-static/**",
      "**/*.config.*",
    ],
  },
  prettierConfig,
];

/**
 * Opt-in type-aware rules. Requires the consumer to pass `tsconfigRootDir`
 * so TypeScript can locate `tsconfig.json`.
 */
export function typeCheckedConfig({ tsconfigRootDir }) {
  return [
    ...tseslint.configs.recommendedTypeChecked,
    {
      languageOptions: {
        parserOptions: {
          projectService: true,
          tsconfigRootDir,
        },
      },
      rules: {
        "@typescript-eslint/no-misused-promises": [
          "error",
          { checksVoidReturn: { attributes: false } },
        ],
      },
    },
  ];
}

export default baseConfig;
