import globals from "globals";
import nPlugin from "eslint-plugin-n";

import { baseConfig } from "./base.js";

export const nodeConfig = [
  ...baseConfig,
  {
    languageOptions: {
      globals: { ...globals.node },
    },
    plugins: { n: nPlugin },
    rules: {
      "n/no-missing-import": "off",
      "n/no-unsupported-features/node-builtins": "warn",
    },
  },
];

export default nodeConfig;
