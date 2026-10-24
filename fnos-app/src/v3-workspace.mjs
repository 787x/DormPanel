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
    const store = { dir: directory, identity,
      read: async name => meta.get('workspace', name),
      save: async (name, value) => meta.atomic(() => meta.put('workspace', name, value)),
      commitWorkspace(prepare, context) {
        // Direct Workspace edits are local; Engine supplies its explicit flag.
        // Select the budget BEFORE change(), including nested metadata writes.
        const isLocalTransaction = context?.local ?? true, previous = meta.budget;
        ensure(typeof isLocalTransaction === 'boolean', 'v3 transaction kind must be boolean');
        if (isLocalTransaction) meta.budget = new Budget({ requests: 0 });
        try { return meta.atomic(() => { const result = prepare(); meta.put('workspace', 'workspace.json', result.next); return result; }); }
        finally { if (isLocalTransaction) meta.budget = previous; }
      } };
    const workspace = await Workspace.open(store, null); ensure(!workspace.frame.datasetId, 'v3 adapter refuses production v2 workspace');
    workspace.guard = writable; const engineFrames = new WeakSet();
    workspace.mutationGuard = (previous, next) => {
      writable(); const internal = engineFrames.delete(next);
      if (!internal && previous.v3 && !same(previous.local, next.local)) next.v3 = { ...previous.v3, revision: previous.v3.revision + 1, decision: null };
    };
    const journal = { directory, meta, networkQueue: Promise.resolve(), prepareReceipt: null,
      get value() { meta.assertOpen(); const frame = workspace.snapshot(); return { ...(frame.v3 ?? { version: 2, pending: null, revision: 0 }), local: frame.local }; },
      async transaction(change, isLocalTransaction = false) {
        ensure(typeof isLocalTransaction === 'boolean', 'v3 transaction kind must be boolean');
        await workspace.tx(frame => {
          const next = { ...(frame.v3 ?? { version: 2, pending: null, revision: 0 }), local: frame.local };
          const result = change(next); ensure(!(result instanceof Promise), 'v3 transaction must be synchronous');
          const { local: courseState, ...control } = next; frame.local = courseState; frame.v3 = control;
          // MutationGuard runs synchronously after change, before the same SQLite commit.
          engineFrames.add(frame);
        }, undefined, { local: isLocalTransaction });
        return this.value;
      },
      async close() { await workspace.queue; meta.close(); }
    };
    return { journal, workspace, writerId: identity.writerId };
  } catch (e) { meta.close(); throw e; }
}
