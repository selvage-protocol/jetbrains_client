// The TypeScript engine the other three clients share, driven over stdin and stdout for the
// cross-implementation test: `vscode_client`'s `RelaySession` under its `PeerEngine`, the same pair
// `vscode_client/test/helpers/live-session.ts` seats, with the host's seeding done as
// `src/bridge/bridge.ts`'s `seedRequested` does it.
//
//   node ts-peer.mjs <vscode_client checkout>
//
// One JSON object per line in, one reply per line out, in order; this side never speaks first.
// The caller does the waiting by asking for `report` until the state it wants is there.
//
//   {"op":"host","base":"ws://…","name":"Ada","files":{"a.md":"…"},"keepalive":{…}}
//   {"op":"join","invite":"ws://…#k=…&h=…","name":"Bob","keepalive":{…},"role":"viewer"}
//   {"op":"open","path":"a.md"}
//   {"op":"insert","path":"a.md","index":0,"text":"…"}
//   {"op":"delete","path":"a.md","index":0,"length":1}
//   {"op":"select","path":"a.md","anchor":3,"head":5}
//   {"op":"rename","name":"Robert"}
//   {"op":"grant","files":{"a.md":"…"}}           (a host: the listing it shares, replaced)
//   {"op":"close"}                                (a host: the room's closing)
//   {"op":"burst","path":"a.md","seed":7,"count":100}   (edits back to back, see `burst`)
//   {"op":"report"}
//   {"op":"leave"}
//   {"op":"quit"}
//
// Every reply is {"ok":true,…} or {"ok":false,"error":"…"}; a refused command is answered and not
// fatal. The `.ts` sources are loaded with Node's own type stripping, as `vscode_client`'s own
// `node --test test/*.ts` loads them (Node 22.18 or later).

import { createInterface } from 'node:readline';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const root = process.argv[2];
if (root === undefined || !existsSync(join(root, 'src', 'engine', 'relay.ts'))) {
  process.stderr.write(`usage: node ts-peer.mjs <vscode_client checkout>; got ${root}\n`);
  process.exit(2);
}

const load = (relative) => import(pathToFileURL(join(root, relative)).href);
const { RelaySession } = await load('src/engine/relay.ts');
const { PeerEngine } = await load('src/bridge/peer-engine.ts');

/** The most events a report carries: a session in a test emits a few dozen. */
const MAX_EVENTS = 1000;

let relay;
let engine;
let files = {};
let listing = [];
const requested = new Set();
const events = [];

function record(event) {
  if (events.length >= MAX_EVENTS) {
    events.shift();
  }
  switch (event.type) {
    case 'hostDetached':
      events.push({ type: event.type, graceMs: event.graceMs });
      return;
    case 'roomGone':
      events.push({ type: event.type, reason: event.reason });
      return;
    case 'sessionError':
      events.push({ type: event.type, code: event.code, message: event.message });
      return;
    case 'documentChanged':
      events.push({ type: event.type, path: event.path });
      return;
    case 'documentsChanged':
      events.push({ type: event.type, documents: event.documents });
      return;
    case 'grantChanged':
      events.push({ type: event.type, paths: event.paths });
      return;
    default:
      events.push({ type: event.type });
  }
}

/**
 * The host's half of `bridge.ts`'s `seedRequested`: a path the room holds open that this host
 * lists, that it was not asked for already and that the replica has no text for, is read and
 * inserted once.
 */
function seed(documents) {
  if (relay === undefined || !relay.isHost) {
    return;
  }
  for (const path of documents) {
    if (requested.has(path) || !Object.hasOwn(files, path)) {
      continue;
    }
    requested.add(path);
    if (!engine.has(path)) {
      engine.insert(path, 0, files[path]);
    }
  }
}

function attach(seated, displayName) {
  relay = seated;
  engine = new PeerEngine({ relay, displayName });
  engine.on((event) => {
    record(event);
    if (event.type === 'documentsChanged') {
      seed(event.documents);
    }
  });
}

function keepalive(command) {
  const value = command.keepalive;
  return typeof value === 'object' && value !== null ? { keepalive: value } : {};
}

function texts() {
  const paths = new Set([...relay.documents(), ...relay.listing(), ...relay.heldPaths()]);
  const out = {};
  for (const path of [...paths].sort()) {
    if (relay.has(path)) {
      out[path] = relay.text(path);
    }
  }
  return out;
}

function presence() {
  return relay.presence().map((record) => {
    const state = record.state ?? {};
    const resolved =
      state.path !== undefined && state.selection !== undefined
        ? (relay.resolveSelection(state.path, state.selection) ?? null)
        : null;
    return {
      clientId: record.clientId,
      displayName: record.peer?.display_name ?? null,
      path: state.path ?? null,
      resolved,
    };
  });
}

function report() {
  if (relay === undefined) {
    return { seated: false, events };
  }
  return {
    seated: true,
    seat: relay.selfInfo().peer_id,
    displayName: relay.selfInfo().display_name,
    awarenessClientId: relay.awarenessClientId() ?? null,
    role: relay.appliedRole() ?? null,
    invite: relay.invite() ?? null,
    listing: [...relay.listing()],
    documents: engine.session().documents,
    texts: texts(),
    peers: relay.peerInfos(),
    presence: presence(),
    hostGraceMs: relay.hostAwayGraceMs() ?? null,
    ending: relay.end ?? null,
    endingSentence: relay.endingSentence() ?? null,
    events,
  };
}

/** The pieces a burst types: one and several code units, a surrogate pair among them. */
const PIECES = ['a', 'bc', '😀', 'é', '\n', 'xyz'];

/** An offset moved off the middle of a surrogate pair, so an edit keeps whole code points. */
function whole(text, at) {
  const unit = text.charCodeAt(at - 1);
  return at > 0 && at < text.length && unit >= 0xd800 && unit <= 0xdbff ? at - 1 : at;
}

/**
 * `count` edits back to back, each placed against this replica as it is at that moment: a third
 * of them deletions of one to three code units, the rest insertions of a piece. The choices come
 * from the 48-bit linear congruential generator `CrossImplementationTest`'s own burst uses.
 */
async function burst(path, seed, count) {
  let state = BigInt(seed);
  const next = (bound) => {
    state = (state * 25214903917n + 11n) & ((1n << 48n) - 1n);
    return Number((state >> 17n) % BigInt(bound));
  };
  for (let i = 0; i < count; i += 1) {
    const text = relay.text(path);
    if (text.length > 0 && next(3) === 0) {
      const start = whole(text, next(text.length));
      const end = whole(text, Math.min(text.length, start + 1 + next(3)));
      if (end > start) {
        await relay.remove(path, start, end - start);
      }
    } else {
      await relay.insert(path, whole(text, next(text.length + 1)), PIECES[next(PIECES.length)]);
    }
  }
}

function need(command, member, type) {
  const value = command[member];
  if (typeof value !== type) {
    throw new Error(`\`${command.op}\` needs \`${member}\` as a ${type}`);
  }
  return value;
}

function seated() {
  if (relay === undefined) {
    throw new Error('no session: `host` or `join` first');
  }
}

async function serve(command) {
  switch (command.op) {
    case 'host': {
      if (relay !== undefined) {
        throw new Error('a session is already running');
      }
      files = { ...need(command, 'files', 'object') };
      const name = need(command, 'name', 'string');
      listing = Object.keys(files);
      attach(
        await RelaySession.host({
          baseUrl: need(command, 'base', 'string'),
          displayName: name,
          listing: () => listing,
          client: 'selvage-jetbrains-crossing',
          ...keepalive(command),
        }),
        name,
      );
      return { invite: relay.invite() };
    }
    case 'join': {
      if (relay !== undefined) {
        throw new Error('a session is already running');
      }
      const name = need(command, 'name', 'string');
      attach(
        await RelaySession.join({
          invite: need(command, 'invite', 'string'),
          displayName: name,
          client: 'selvage-jetbrains-crossing',
          ...(command.role === 'guest' || command.role === 'viewer' ? { declaredRole: command.role } : {}),
          ...keepalive(command),
        }),
        name,
      );
      return {};
    }
    case 'open':
      seated();
      await engine.open(need(command, 'path', 'string'));
      return {};
    case 'insert': {
      seated();
      const path = need(command, 'path', 'string');
      const published = await relay.insert(path, need(command, 'index', 'number'), need(command, 'text', 'string'));
      return { published, text: relay.text(path) };
    }
    case 'delete': {
      seated();
      const path = need(command, 'path', 'string');
      const published = await relay.remove(path, need(command, 'index', 'number'), need(command, 'length', 'number'));
      return { published, text: relay.text(path) };
    }
    case 'select':
      seated();
      engine.setSelection(need(command, 'path', 'string'), {
        anchor: need(command, 'anchor', 'number'),
        head: need(command, 'head', 'number'),
      });
      return {};
    case 'rename':
      seated();
      await engine.rename(need(command, 'name', 'string'));
      return {};
    case 'grant':
      seated();
      files = { ...need(command, 'files', 'object') };
      listing = Object.keys(files);
      await engine.grant(listing);
      return {};
    case 'close':
      seated();
      return { closed: await engine.closeRoom() };
    case 'burst': {
      seated();
      const path = need(command, 'path', 'string');
      await burst(path, need(command, 'seed', 'number'), need(command, 'count', 'number'));
      return { text: relay.text(path) };
    }
    case 'report':
      return report();
    case 'leave':
      seated();
      await engine.disconnect();
      return {};
    case 'quit':
      return undefined;
    default:
      throw new Error(`unknown op ${JSON.stringify(command.op)}`);
  }
}

const write = (value) => {
  process.stdout.write(`${JSON.stringify(value)}\n`);
};

const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
for await (const line of lines) {
  if (line.trim() === '') {
    continue;
  }
  let command;
  try {
    command = JSON.parse(line);
  } catch (error) {
    write({ ok: false, error: `a command is one JSON object per line: ${String(error)}` });
    continue;
  }
  let reply;
  try {
    reply = await serve(command);
  } catch (error) {
    write({ ok: false, error: error instanceof Error ? error.message : String(error) });
    continue;
  }
  if (reply === undefined) {
    write({ ok: true });
    break;
  }
  write({ ok: true, ...reply });
}
// The caller closed stdin or said quit: the socket and the clocks go with the process.
engine?.disconnect();
process.exit(0);
