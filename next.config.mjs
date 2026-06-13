/** @type {import('next').NextConfig} */
const nextConfig = {
  // The chat route spawns the `claude` CLI and streams its output, so it must
  // run on the Node.js runtime (not the Edge runtime).
  experimental: {
    // Keep server actions snappy; nothing here needs the default body limit.
  },
};

export default nextConfig;
