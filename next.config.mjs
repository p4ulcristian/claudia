/** @type {import('next').NextConfig} */
const nextConfig = {
  // The chat + usage routes spawn the `claude` CLI and stream its output, so
  // they run on the Node.js runtime (not Edge). node-pty is a native module and
  // must not be bundled — keep it external to the server build.
  serverExternalPackages: ["node-pty"],
};

export default nextConfig;
