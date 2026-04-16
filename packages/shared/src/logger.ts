import pino, { type Logger, type LoggerOptions } from "pino";

export type LogLevel = "fatal" | "error" | "warn" | "info" | "debug" | "trace";

export interface CreateLoggerOptions {
  level?: LogLevel;
  service: string;
  env?: string;
  base?: Record<string, unknown>;
}

const isDev = (env: string | undefined): boolean =>
  env === undefined || env === "development" || env === "test";

export function createLogger(options: CreateLoggerOptions): Logger {
  const { level = "info", service, env = process.env.NODE_ENV, base } = options;

  const pinoOptions: LoggerOptions = {
    level,
    base: {
      service,
      env: env ?? "development",
      ...base,
    },
    timestamp: pino.stdTimeFunctions.isoTime,
    ...(isDev(env)
      ? {
          transport: {
            target: "pino-pretty",
            options: { colorize: true, translateTime: "SYS:standard" },
          },
        }
      : {}),
  };

  return pino(pinoOptions);
}

export type { Logger };
