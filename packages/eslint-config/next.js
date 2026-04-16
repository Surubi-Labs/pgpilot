import { reactConfig } from "./react.js";

export const nextConfig = [
  ...reactConfig,
  {
    rules: {
      "@next/next/no-html-link-for-pages": "off",
    },
  },
];

export default nextConfig;
