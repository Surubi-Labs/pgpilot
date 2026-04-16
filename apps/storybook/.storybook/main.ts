import type { StorybookConfig } from "@storybook/react-vite";

const config: StorybookConfig = {
  stories: [
    "../src/**/*.mdx",
    "../src/**/*.stories.@(ts|tsx)",
    "../../../packages/ui/src/**/*.stories.@(ts|tsx)",
  ],
  addons: ["@storybook/addon-essentials", "@storybook/addon-a11y", "@storybook/addon-viewport"],
  framework: {
    name: "@storybook/react-vite",
    options: {},
  },
  typescript: {
    reactDocgen: "react-docgen-typescript",
  },
  core: {
    disableTelemetry: true,
  },
};

export default config;
