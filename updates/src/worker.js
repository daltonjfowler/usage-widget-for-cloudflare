// Serve the static assets, but never let a client or CDN cache latest.json:
// the phone must see the current version the moment Dalton publishes one. The
// APK is content-addressed by the SHA-256 in latest.json, so it is free to cache.
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const response = await env.ASSETS.fetch(request);
    if (url.pathname === "/latest.json") {
      const headers = new Headers(response.headers);
      headers.set("Cache-Control", "no-store");
      return new Response(response.body, { status: response.status, headers });
    }
    return response;
  }
};
