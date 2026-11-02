// Files the page imports as URLs (Bun copies them next to the bundle and hands back their path).
declare module "*.glb" {
  const url: string;
  export default url;
}
