// static-server.mjs — tiny, zero-dependency static file server for the e2e harness.
//
// WHY this exists (and why it isn't `npx serve`): the production web front-end is
// served by Firebase Hosting, whose `firebase.json` rewrites map the CLEAN paths
// `/status` -> `/status.html` and `/ops` -> `/ops.html`, with an SPA catch-all
// `**` -> `/index.html`. A plain static server would 404 on `/status` and `/ops`
// and would not fall back for deep routes, so the e2e specs could not exercise the
// real clean paths. This server replicates exactly those three rewrite rules over
// the committed `web/` directory, with NO npm dependencies — so the harness stays
// small, fast to install, and reproducible in CI.
//
// It serves the parent directory (`web/`, one level up from `web/e2e/`) as the web
// root, exactly the same set of files Firebase Hosting ships.
//
// Config: PORT (env, default 4310). Prints "listening on http://localhost:PORT"
// once bound so Playwright's `webServer` can wait on that URL.

import http from "node:http";
import { readFile, stat } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// Web root = the committed `web/` directory (the parent of this `web/e2e/` folder).
const ROOT = path.resolve(__dirname, "..");
const PORT = Number(process.env.PORT || 4310);

// Minimal content-type table — only the types the static pages actually use.
const CONTENT_TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".map": "application/json; charset=utf-8",
};

// The Firebase Hosting rewrites we must mirror (see firebase.json). Clean path ->
// the real committed file. Anything not matched here and not a real file on disk
// falls back to index.html (the SPA catch-all).
const REWRITES = {
  "/status": "/status.html",
  "/ops": "/ops.html",
  "/help": "/help.html",
  "/app": "/app.html",
  "/history": "/history.html",
  "/diagnostics": "/diagnostics.html",
};

// Resolve a request URL path to an absolute file path INSIDE ROOT, or null if the
// (normalised) path would escape the web root — a path-traversal guard.
function resolveWithinRoot(urlPath) {
  // Normalise away any `..` segments, then join to ROOT and re-check containment.
  const clean = path.normalize(decodeURIComponent(urlPath)).replace(/^(\.\.[/\\])+/, "");
  const abs = path.join(ROOT, clean);
  if (abs !== ROOT && !abs.startsWith(ROOT + path.sep)) {
    return null; // escaped the root — refuse.
  }
  return abs;
}

// Return the on-disk file to serve for a request path, applying: explicit rewrite
// -> real file -> directory index.html -> SPA fallback to /index.html.
async function pickFile(urlPath) {
  // 1) Explicit clean-path rewrite (/status, /ops).
  if (REWRITES[urlPath]) {
    return resolveWithinRoot(REWRITES[urlPath]);
  }
  // 2) A real file on disk (e.g. /index.html, /config.js, /status.html).
  const direct = resolveWithinRoot(urlPath);
  if (direct) {
    try {
      const s = await stat(direct);
      if (s.isFile()) {
        return direct;
      }
      // Directory request -> its index.html if present.
      if (s.isDirectory()) {
        const idx = path.join(direct, "index.html");
        try {
          if ((await stat(idx)).isFile()) {
            return idx;
          }
        } catch {
          /* no directory index — fall through to SPA fallback */
        }
      }
    } catch {
      /* not on disk — fall through to SPA fallback */
    }
  }
  // 3) SPA catch-all: unknown route -> index.html (mirrors firebase.json "**").
  return resolveWithinRoot("/index.html");
}

const server = http.createServer(async (req, res) => {
  try {
    // Strip query string / hash before resolving to a file.
    const urlPath = (req.url || "/").split("?")[0].split("#")[0] || "/";
    const filePath = await pickFile(urlPath === "/" ? "/index.html" : urlPath);
    if (!filePath) {
      res.writeHead(403).end("Forbidden");
      return;
    }
    const body = await readFile(filePath);
    const type = CONTENT_TYPES[path.extname(filePath).toLowerCase()] || "application/octet-stream";
    // no-store keeps every e2e run honest (never a cached page across runs).
    res.writeHead(200, { "Content-Type": type, "Cache-Control": "no-store" }).end(body);
  } catch (err) {
    res.writeHead(500).end("Internal Server Error");
  }
});

server.listen(PORT, () => {
  // Playwright's webServer waits for this URL to answer before running the specs.
  console.log(`static-server listening on http://localhost:${PORT} (root: ${ROOT})`);
});
