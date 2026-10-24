import { randomUUID } from 'node:crypto';
import { ensure } from './protocol.mjs';
import { same } from './merge.mjs';
import { Workspace } from './workspace.mjs';
import { V3Metadata } from './v3-metadata.mjs';
import { Budget } from './v3-store.mjs';

/** Opt-in PR55 transaction adapter. Reuses Workspace edit/revision/guard rules; local
 * courses and V3 control live in ONE SQLite-backed Workspace row. No v2 import/binding.
 * Caller supplies the catalog/administrator/directory guard; no routes invoke this yet. */
export async function openV3Workspace(directory, writable) {
  ensure(typeof writable === 'function', 'v3 Workspace requires permission guard'); writable();
  const meta = new V3Metadata(directory); meta.guard = writable;
  try {
    ensure(!meta.get('state', 'frame'), 'v3 standalone state cannot silently become a Workspace');
    let identity = meta.get('installation', 'identity');
    if (!identity) { identity = { writerId: randomUUID(), installationId: randomUUID() }; meta.atomic(() => meta.put('installation', 'identity', identity)); }
    const localFrames = new WeakSet();
    const store = { dir: directory, identity,
      read: async name => meta.get('workspace', name),
      save: async (name, value) => { const local = name === 'workspace.json' && localFrames.delete(value), previous = meta.budget; if (local) meta.budget = new Budget({ requests: 0 }); try { meta.atomic(() => meta.put('workspace', name, value)); } finally { if (local) meta.budget = previous; } } };
    const workspace = await Workspace.open(store, null); ensure(!workspace.frame.datasetId, 'v3 adapter refuses production v2 workspace');
    workspace.guard = writable; const engineFrames = new WeakSet();
    workspace.mutationGuard = (previous, next) => {
      writable(); const internal = engineFrames.delete(next);
      if (!internal) localFrames.add(next);
      if (!internal && previous.v3 && !same(previous.local, next.local)) next.v3 = { ...previous.v3, revision: previous.v3.revision + 1, decision: null };
    };
    const journal = { directory, meta, networkQueue: Promise.resolve(), prepareReceipt: null,
      get value() { meta.assertOpen(); const frame = workspace.snapshot(); return { ...(frame.v3 ?? { version: 2, pending: null, revision: 0 }), local: frame.local }; },
      async transaction(change, local = false) {
        await workspace.tx(frame => {
          const next = { ...(frame.v3 ?? { version: 2, pending: null, revision: 0 }), local: frame.local };
          const result = change(next); ensure(!(result instanceof Promise), 'v3 transaction must be synchronous');
          const { local, ...control } = next; frame.local = local; frame.v3 = control;
          // MutationGuard runs synchronously after change, before the same SQLite commit.
          engineFrames.add(frame);
          if (local) localFrames.add(frame);
        });
        return this.value;
      },
      async close() { await workspace.queue; meta.close(); }
    };
    return { journal, workspace, writerId: identity.writerId };
  } catch (e) { meta.close(); throw e; }
}
