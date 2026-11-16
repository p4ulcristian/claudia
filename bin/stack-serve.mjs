#!/usr/bin/env node
// The test stack (bin/stack.mjs) kept up until stopped, behind the sign-in proxy on a fixed port:
// for opening the page by hand or from an app build whose hub address is baked in.
//   node bin/stack-serve.mjs [port]     (default 4599)
import { startStack, startProxy } from './stack.mjs';
const stack = await startStack({ listen: true });
const proxy = await startProxy(stack.hubPort, Number(process.argv[2] || 4599));
console.log(`stack-serve: ${proxy.url}`);
setInterval(() => { for (const r of proxy.reports.splice(0)) console.log('stack-serve: report', JSON.stringify(r)); }, 1000);
const stop = () => { proxy.close(); stack.stop(); process.exit(0); };
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
