import { color, fontSize, space } from "@pgpilot/tokens";
import type { Meta, StoryObj } from "@storybook/react";
import type { CSSProperties, ReactElement } from "react";

/**
 * Introduction / smoke story. Exercises design-token consumption from
 * @pgpilot/tokens so the Storybook build is a real end-to-end check of
 * the monorepo wiring (workspace dep, CSS variables, TS map).
 */

type Swatch = {
  name: string;
  value: string;
};

function TokenGrid({ title, swatches }: { title: string; swatches: Swatch[] }): ReactElement {
  return (
    <section style={{ marginBlock: space["space-8"] }}>
      <h2
        style={{
          fontFamily: "var(--pg-font-serif)",
          fontSize: fontSize["text-2xl"],
          letterSpacing: "var(--pg-tracking-tight)",
          marginBlockEnd: space["space-4"],
        }}
      >
        {title}
      </h2>
      <div
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(auto-fill, minmax(180px, 1fr))",
          gap: space["space-3"],
        }}
      >
        {swatches.map((swatch) => (
          <figure
            key={swatch.name}
            style={{ margin: 0, display: "flex", flexDirection: "column", gap: space["space-2"] }}
          >
            <div
              style={{
                background: swatch.value,
                borderRadius: "var(--pg-radius-md)",
                blockSize: "88px",
                border: "1px solid oklch(0% 0 0 / 6%)",
              }}
            />
            <figcaption
              style={{
                fontFamily: "var(--pg-font-mono)",
                fontSize: fontSize["text-xs"],
                color: "var(--pg-neutral-600)",
              }}
            >
              {swatch.name}
            </figcaption>
          </figure>
        ))}
      </div>
    </section>
  );
}

function Introduction(): ReactElement {
  const neutralSwatches: Swatch[] = Object.entries(color)
    .filter(([name]) => name.startsWith("neutral-"))
    .map(([name, value]) => ({ name, value }));

  const accentSwatches: Swatch[] = Object.entries(color)
    .filter(([name]) => name.startsWith("accent-"))
    .map(([name, value]) => ({ name, value }));

  const signalSwatches: Swatch[] = ["success", "warning", "danger", "info"].map((key) => ({
    name: key,
    value: color[key as keyof typeof color],
  }));

  const wrapperStyle: CSSProperties = {
    padding: space["space-8"],
    maxInlineSize: "72rem",
    marginInline: "auto",
  };

  return (
    <main style={wrapperStyle}>
      <header style={{ marginBlockEnd: space["space-12"] }}>
        <p
          style={{
            fontFamily: "var(--pg-font-mono)",
            fontSize: fontSize["text-xs"],
            letterSpacing: "var(--pg-tracking-wider)",
            textTransform: "uppercase",
            color: "var(--pg-neutral-500)",
            marginBlockEnd: space["space-2"],
          }}
        >
          @pgpilot/tokens
        </p>
        <h1
          style={{
            fontFamily: "var(--pg-font-serif)",
            fontSize: fontSize["text-4xl"],
            letterSpacing: "var(--pg-tracking-tighter)",
            marginBlock: 0,
          }}
        >
          PgPilot design language
        </h1>
        <p
          style={{
            fontSize: fontSize["text-lg"],
            color: "var(--pg-neutral-700)",
            maxInlineSize: "52ch",
            marginBlockStart: space["space-4"],
          }}
        >
          Editorial direction, restrained palette. Tokens are the source of truth; everything
          downstream (dashboard, landing, docs) inherits from this layer.
        </p>
      </header>

      <TokenGrid title="Neutral ramp" swatches={neutralSwatches} />
      <TokenGrid title="Accent" swatches={accentSwatches} />
      <TokenGrid title="Signals" swatches={signalSwatches} />
    </main>
  );
}

const meta: Meta<typeof Introduction> = {
  title: "Foundations/Introduction",
  component: Introduction,
  parameters: { layout: "fullscreen" },
};

export default meta;

export const Default: StoryObj<typeof Introduction> = {};
