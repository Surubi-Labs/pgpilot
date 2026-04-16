import type { Preview } from "@storybook/react";

import "@pgpilot/tokens/tokens.css";
import "./preview.css";

const preview: Preview = {
  parameters: {
    controls: {
      matchers: { color: /(background|color)$/i, date: /Date$/i },
    },
    backgrounds: {
      default: "surface",
      values: [
        { name: "surface", value: "oklch(99% 0 0)" },
        { name: "neutral-100", value: "oklch(92% 0.005 255)" },
        { name: "neutral-900", value: "oklch(14% 0.015 255)" },
      ],
    },
    viewport: {
      viewports: {
        xs: { name: "xs (320)", styles: { width: "320px", height: "568px" } },
        sm: { name: "sm (480)", styles: { width: "480px", height: "800px" } },
        md: { name: "md (768)", styles: { width: "768px", height: "1024px" } },
        lg: { name: "lg (1024)", styles: { width: "1024px", height: "768px" } },
        xl: { name: "xl (1440)", styles: { width: "1440px", height: "900px" } },
      },
    },
    a11y: {
      config: {
        rules: [{ id: "color-contrast", enabled: true }],
      },
    },
  },
};

export default preview;
