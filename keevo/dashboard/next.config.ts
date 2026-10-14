import type { NextConfig } from "next";

// Where the server-side proxy `/api/backend/*` sends requests.
// IMPORTANT: Next.js evaluates rewrites() at BUILD time. The destination is
// written into .next/routes-manifest.json and the standalone server.js, so
// changing NEXT_PUBLIC_API_URL when the container starts has NO effect. The
// Docker image (see Dockerfile) bakes the internal Docker DNS name
// http://backend:4500 (compose service `backend`, same name in prod and
// staging), never a public URL. `npm run dev` keeps the localhost default.
const backendUrl = process.env.NEXT_PUBLIC_API_URL || "http://localhost:4500";

const nextConfig: NextConfig = {
  // Self-contained server (.next/standalone/server.js) for the Docker image.
  output: "standalone",
  async rewrites() {
    return [
      {
        source: "/api/backend/:path*",
        destination: `${backendUrl}/api/v1/:path*`,
      },
    ];
  },
};

export default nextConfig;
