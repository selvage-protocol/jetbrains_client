// Drives real yjs for the engine's differential tests: one JSON request per stdin line, one JSON
// reply per stdout line. Bytes travel as lowercase hex.
//
// The node_modules directory holding yjs and y-protocols is the first argument, or
// SELVAGE_YJS_NODE_MODULES.

import { createInterface } from 'node:readline';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { readFileSync } from 'node:fs';

const nodeModules = process.argv[2] ?? process.env.SELVAGE_YJS_NODE_MODULES;
if (!nodeModules) {
  process.stderr.write('yjs-driver: no node_modules directory given\n');
  process.exit(2);
}
const load = (relative) => import(pathToFileURL(join(nodeModules, relative)).href);
const Y = await load('yjs/dist/yjs.mjs');
const awarenessProtocol = await load('y-protocols/awareness.js');
const version = (name) => JSON.parse(readFileSync(join(nodeModules, name, 'package.json'), 'utf8')).version;

const hex = (bytes) => Buffer.from(bytes).toString('hex');
const bytes = (text) => new Uint8Array(Buffer.from(text, 'hex'));

/** @type {Map<string, {doc: Y.Doc, updates: string[], events: object[]}>} */
const docs = new Map();

const entry = (name) => {
  const e = docs.get(name);
  if (!e) throw new Error(`no document ${name}`);
  return e;
};

const deltaOf = (delta) =>
  delta.map((op) => {
    if (op.insert !== undefined) {
      return typeof op.insert === 'string' ? { insert: op.insert } : { embed: true };
    }
    return op.retain !== undefined ? { retain: op.retain } : { delete: op.delete };
  });

// Content kinds the engine carries without an API for them, written the way a yjs peer would.
const rich = (doc, path, kind) => {
  const text = doc.getText(path);
  switch (kind) {
    case 'embed':
      text.insertEmbed(Math.min(1, text.length), { image: 'a.png', size: [1, 2.5], ok: true, none: null });
      break;
    case 'format':
      text.insert(text.length, 'bold', { bold: true });
      if (text.length > 2) text.format(0, 2, { italic: { deep: [1, 'x'] } });
      break;
    case 'map': {
      const map = doc.getMap(`${path}#map`);
      map.set('int', 7);
      map.set('negative', -2147483647);
      map.set('float', 0.5);
      map.set('double', 1.1);
      map.set('big', 2 ** 40);
      map.set('string', 'π\u{1F600}');
      map.set('bool', false);
      map.set('null', null);
      map.set('undefined', undefined);
      map.set('array', [1, 'two', [3], { four: 4 }]);
      map.set('object', { 10: 'ten', b: 1, 2: 'two', a: [null] });
      map.set('binary', new Uint8Array([0, 1, 254, 255]));
      map.set('bigint', 12345678901234n);
      map.set('int', 8);
      break;
    }
    case 'array': {
      const array = doc.getArray(`${path}#array`);
      array.push([1, 2, 3, 'x']);
      array.insert(1, [new Uint8Array([9, 8])]);
      array.delete(2, 1);
      break;
    }
    case 'nested': {
      const array = doc.getArray(`${path}#nested`);
      const inner = new Y.Text('inner');
      const map = new Y.Map();
      array.push([inner, map, new Y.Array()]);
      map.set('k', 'v');
      inner.insert(0, '>');
      text.insertEmbed(0, new Y.Text('embedded type'));
      break;
    }
    case 'xml': {
      const fragment = doc.getXmlFragment(`${path}#xml`);
      const element = new Y.XmlElement('p');
      element.setAttribute('class', 'c');
      element.insert(0, [new Y.XmlText('hello')]);
      fragment.insert(0, [element, new Y.XmlHook('hook')]);
      break;
    }
    case 'subdoc': {
      const map = doc.getMap(`${path}#docs`);
      map.set('a', new Y.Doc({ guid: 'sub-a', meta: { m: 1 }, autoLoad: true }));
      map.set('b', new Y.Doc({ guid: 'sub-b', shouldLoad: false }));
      break;
    }
    default:
      throw new Error(`unknown kind ${kind}`);
  }
};

const handlers = {
  versions: () => ({ yjs: version('yjs'), yProtocols: version('y-protocols'), lib0: version('lib0') }),
  new: ({ doc, clientID, gc = true }) => {
    const d = new Y.Doc({ gc });
    d.clientID = clientID;
    const e = { doc: d, updates: [], events: [] };
    d.on('update', (update) => e.updates.push(hex(update)));
    docs.set(doc, e);
    return {};
  },
  observe: ({ doc, path }) => {
    const e = entry(doc);
    e.doc.getText(path).observe((event) => {
      e.events.push({ path, local: event.transaction.local, origin: event.transaction.origin ?? null, delta: deltaOf(event.delta) });
    });
    return {};
  },
  events: ({ doc }) => {
    const e = entry(doc);
    return { events: e.events.splice(0) };
  },
  insert: ({ doc, path, index, text, origin }) => {
    const d = entry(doc).doc;
    d.transact(() => d.getText(path).insert(index, text), origin ?? null);
    return {};
  },
  delete: ({ doc, path, index, length, origin }) => {
    const d = entry(doc).doc;
    d.transact(() => d.getText(path).delete(index, length), origin ?? null);
    return {};
  },
  rich: ({ doc, path, kind }) => {
    rich(entry(doc).doc, path, kind);
    return {};
  },
  apply: ({ doc, update, origin }) => {
    Y.applyUpdate(entry(doc).doc, bytes(update), origin ?? 'remote');
    return {};
  },
  text: ({ doc, path }) => ({ text: entry(doc).doc.getText(path).toString() }),
  sv: ({ doc }) => ({ sv: hex(Y.encodeStateVector(entry(doc).doc)) }),
  state: ({ doc, sv }) => ({ update: hex(Y.encodeStateAsUpdate(entry(doc).doc, sv === undefined ? undefined : bytes(sv))) }),
  updates: ({ doc }) => ({ updates: entry(doc).updates.splice(0) }),
  pending: ({ doc }) => {
    const store = entry(doc).doc.store;
    return { structs: store.pendingStructs !== null, ds: store.pendingDs !== null };
  },
  merge: ({ updates }) => ({ update: hex(Y.mergeUpdates(updates.map(bytes))) }),
  diff: ({ update, sv }) => ({ update: hex(Y.diffUpdate(bytes(update), bytes(sv))) }),
  svOfUpdate: ({ update }) => ({ sv: hex(Y.encodeStateVectorFromUpdate(bytes(update))) }),
  // Decoding to v2 and back re-encodes every struct and content: the canonical form yjs writes.
  reencode: ({ update }) => ({ update: hex(Y.convertUpdateFormatV2ToV1(Y.convertUpdateFormatV1ToV2(bytes(update)))) }),
  relpos: ({ doc, path, index, assoc = 0 }) => {
    const rpos = Y.createRelativePositionFromTypeIndex(entry(doc).doc.getText(path), index, assoc);
    return { rpos: Y.relativePositionToJSON(rpos) };
  },
  resolve: ({ doc, rpos }) => {
    const abs = Y.createAbsolutePositionFromRelativePosition(Y.createRelativePositionFromJSON(rpos), entry(doc).doc);
    return { index: abs === null ? null : abs.index };
  },
  awarenessEncode: ({ clientID, states }) => {
    const d = new Y.Doc();
    d.clientID = clientID;
    const awareness = new awarenessProtocol.Awareness(d);
    clearInterval(awareness._checkInterval);
    for (const state of states) awareness.setLocalState(state);
    const update = hex(awarenessProtocol.encodeAwarenessUpdate(awareness, [clientID]));
    awareness.destroy();
    return { update };
  },
  awarenessApply: ({ updates }) => {
    const d = new Y.Doc();
    d.clientID = 0;
    const awareness = new awarenessProtocol.Awareness(d);
    clearInterval(awareness._checkInterval);
    const changes = [];
    awareness.on('change', (change) => changes.push(change));
    for (const update of updates) awarenessProtocol.applyAwarenessUpdate(awareness, bytes(update), 'remote');
    const states = [];
    awareness.getStates().forEach((state, client) => {
      states.push({ client, clock: awareness.meta.get(client).clock, state });
    });
    awareness.destroy();
    return { states, changes };
  },
};

const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
for await (const line of lines) {
  if (line.trim() === '') continue;
  let reply;
  try {
    const request = JSON.parse(line);
    const handler = handlers[request.op];
    if (!handler) throw new Error(`unknown op ${request.op}`);
    reply = { ok: true, ...handler(request) };
  } catch (e) {
    reply = { ok: false, error: String(e && e.stack ? e.stack : e) };
  }
  process.stdout.write(`${JSON.stringify(reply)}\n`);
}
