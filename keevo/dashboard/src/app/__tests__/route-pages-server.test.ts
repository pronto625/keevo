// @vitest-environment node
import { readdirSync, readFileSync } from "node:fs";
import { join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";
import type { Metadata } from "next";
import type { ReactElement } from "react";
import { describe, expect, it } from "vitest";

const APP_DIR = fileURLToPath(new URL("..", import.meta.url));
const ROUTE_FILE = /^(page|layout|template)\.(tsx|ts|jsx|js)$/;

function findRouteFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      return entry.name === "__tests__" ? [] : findRouteFiles(path);
    }
    return ROUTE_FILE.test(entry.name) ? [path] : [];
  });
}

const toKey = (path: string) => relative(APP_DIR, path).split(sep).join("/");

/** Route segment files, with the French title each page sets (layout template « Keevo Admin — %s »). */
const PAGE_TITLES: Record<string, string> = {
  "alertes/page.tsx": "Alertes",
  "analytics/page.tsx": "Statistiques",
  "login/page.tsx": "Connexion",
  "monitoring/activity/page.tsx": "Activité",
  "monitoring/products/page.tsx": "Catalogue Produits",
  "monitoring/sync/page.tsx": "Synchronisation",
  "notifications/page.tsx": "Notifications",
  "retours/page.tsx": "Retours utilisateurs",
  "revenus/page.tsx": "Revenus",
  "sante/page.tsx": "Santé système",
  "tenants/page.tsx": "Entreprises",
};
/** Redirect-only root page: renders nothing, so it carries no title. */
const UNTITLED_PAGES = ["page.tsx"];
/** Pages whose shell renders a component other than a sibling `*PageClient`. */
const NON_CLIENT_SHELLS = ["login/page.tsx"];
const LAYOUTS = ["layout.tsx"];

const CLIENT_DIRECTIVE = /^\s*(?:\/\/[^\n]*\n\s*|\/\*[\s\S]*?\*\/\s*)*["']use client["']/;

const files = findRouteFiles(APP_DIR);
const pages = files.map(toKey).filter((key) => /(^|\/)page\.[jt]sx?$/.test(key));

describe("route segment files are server components (story 15.7)", () => {
  it("discovers exactly the expected route files", () => {
    expect(new Set(files.map(toKey))).toEqual(
      new Set([...Object.keys(PAGE_TITLES), ...UNTITLED_PAGES, ...LAYOUTS]),
    );
  });

  it.each(files.map((path) => [toKey(path), path]))("%s is not a client module", (_key, path) => {
    expect(CLIENT_DIRECTIVE.test(readFileSync(path, "utf8"))).toBe(false);
  });

  it.each(pages.filter((key) => !UNTITLED_PAGES.includes(key)))(
    "%s exports its French title",
    async (key) => {
      const mod = (await import(join(APP_DIR, key))) as { metadata?: Metadata };
      expect(mod.metadata?.title).toBe(PAGE_TITLES[key]);
    },
  );

  it.each(pages.filter((key) => !UNTITLED_PAGES.includes(key) && !NON_CLIENT_SHELLS.includes(key)))(
    "%s renders its own *PageClient",
    async (key) => {
      const dir = join(APP_DIR, key, "..");
      const clientFiles = readdirSync(dir).filter((name) => /PageClient\.tsx$/.test(name));
      expect(clientFiles).toHaveLength(1);
      const client = (await import(join(dir, clientFiles[0]))) as { default: unknown };
      const page = (await import(join(APP_DIR, key))) as { default: () => ReactElement };
      expect(page.default().type).toBe(client.default);
    },
  );
});
