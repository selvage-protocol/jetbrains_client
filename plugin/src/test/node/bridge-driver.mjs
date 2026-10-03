// Evaluates calls into the VS Code client's bridge for the plugin's parity tests.
//
//   node bridge-driver.mjs <vscode_client checkout>
//
// stdin: one call per line, `<module>\t<export>\t<JSON array of arguments>`, or `<module>\t<export>`
// alone for a constant. stdout: one JSON value per line, in order. A first line `exports` instead
// asks for every export of each module, so a test can tell an export it does not cover.
import { createInterface } from 'node:readline';

const root = process.argv[2];
const modules = {
  words: await import(`${root}/src/bridge/words.ts`),
  seats: await import(`${root}/src/bridge/seats.ts`),
  names: await import(`${root}/src/bridge/names.ts`),
  initials: await import(`${root}/src/bridge/initials.ts`),
  grant: await import(`${root}/src/bridge/grant.ts`),
};

const lines = createInterface({ input: process.stdin });
for await (const line of lines) {
  if (line === '') {
    continue;
  }
  if (line === 'exports') {
    const all = {};
    for (const [name, module] of Object.entries(modules)) {
      all[name] = Object.keys(module).sort();
    }
    console.log(JSON.stringify(all));
    continue;
  }
  const [module, name, args] = line.split('\t');
  const value = modules[module][name];
  const result = args === undefined ? value : value(...JSON.parse(args));
  console.log(JSON.stringify(result instanceof Map ? Object.fromEntries(result) : result));
}
