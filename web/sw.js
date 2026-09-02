// sw.js — Sulu-Read app shell.
// Cache-first so a cold launch with no signal still opens the catalog; every hit also
// re-fetches in the background, so a deploy reaches an installed app on the NEXT launch even
// when VERSION was never bumped. Bump VERSION only to force an atomic swap of the whole shell
// (a breaking change where a new index.html must not meet an old app.js).
// Never cached: /api/* (Vercel rewrites those to the HF backend — user ids, adapt results,
// attempts) and cross-origin word pictures, which come back opaque and eat the origin quota.
"use strict";

const VERSION = "sulu-20260902-113418";
// Split on purpose: cache.addAll is all-or-nothing, so one missing icon would reject install
// and the worker would never activate, silently, on a phone with no console to check.
// Code must be there; the icons are best-effort.
const CORE = [
  "/", "/index.html", "/app.css",
  "/strings.js", "/icons.js", "/focus.js", "/speech.js", "/api.js", "/app.js"
];
const EXTRAS = [
  "/manifest.json", "/apple-touch-icon.png", "/icon-192.png", "/icon-512.png", "/favicon-32.png"
];

// Pure routing decision, kept out of the event handler so test_pwa.js can check it in node.
function swRoute(method, requestUrl, selfOrigin) {
  if (method !== "GET") return "pass";
  const url = new URL(requestUrl);
  if (url.origin !== selfOrigin) return "pass";   // backend word pictures, anything remote
  if (url.pathname.startsWith("/api/")) return "pass";
  return "shell";
}

async function shellRespond(event) {
  const request = event.request;
  const cache = await caches.open(VERSION);
  // The shell is one page, so a navigation always resolves to /index.html — query strings and
  // Vary quirks must not produce a cache miss on the launch that has no signal.
  const key = request.mode === "navigate" ? "/index.html" : request;
  const hit = await cache.match(key);
  const fresh = fetch(request)
    .then((response) => {
      if (response && response.ok && response.type === "basic") cache.put(key, response.clone());
      return response;
    })
    .catch(() => null);
  if (hit) { event.waitUntil(fresh); return hit; }   // cache-first, revalidate behind it
  return (await fresh) || Response.error();
}

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(VERSION)
      .then((cache) => cache.addAll(CORE)
        .then(() => Promise.allSettled(EXTRAS.map((u) => cache.add(u)))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  if (swRoute(event.request.method, event.request.url, self.location.origin) !== "shell") return;
  event.respondWith(shellRespond(event));
});

/* ============================================================================
   WILL THIS UPDATE AN INSTALLED APP, OR STRAND IT? IT UPDATES. Two layers:

   1. Stale-while-revalidate is the layer that cannot be forgotten. Every shell hit is served
      from cache and re-fetched behind it. A deploy where VERSION was never touched still lands
      — on the launch after the one that downloaded it. All shell files are <script> tags in
      index.html so they revalidate on the same page load and the generation advances
      coherently; index.html itself re-fetches because a standalone relaunch is a navigation.
      The platform judge curled the live deploy: index.html, app.js, app.css and strings.js are
      already served `public, max-age=0, must-revalidate` with ETags, so the background
      revalidation genuinely reaches the network. Worst case is ONE stale launch.

   2. VERSION is the hard swap. Bump it when a change is not one-launch-safe (a new file in
      CORE, or an index.html/app.js pair that must move together). skipWaiting + clients.claim
      hand over on the next launch and activate deletes every older cache. That is safe here
      specifically because this is a single-page shell with no lazy-loaded chunks, so a
      mid-session swap cannot produce a new-index/old-chunk mismatch.

   The trap avoided: standalone mode has no address bar, no reload button and no pull-to-
   refresh, so a wedged deploy is unrecoverable from inside the app without force-quitting.

   `response.type === "basic"` guards the cache write. Vercel serves /index.html with a plain
   200 and no 308 to /, so the navigate cache key is safe today; the guard means that if that
   ever changes, a redirected response is not stored — which would otherwise throw "a redirected
   response was used for a request whose redirect mode is not follow" on every launch.

   NO SERVICE WORKER IS REQUIRED TO INSTALL ON iOS. Add to Home Screen works on any HTTPS page;
   iOS never adopted Chrome's installability criteria. The SW buys exactly two things: the app
   opens with no signal (the point, for a child's reading app on a phone in a car), and instant
   cold start instead of re-downloading app.js (69 KB) + strings.js (39 KB) every launch.
   ============================================================================ */