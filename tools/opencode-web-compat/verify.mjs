import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import crypto from 'node:crypto';

const source = fs.readFileSync(new URL('../../android/app/src/main/assets/opencode-web-compat.js', import.meta.url), 'utf8');
const metadata = JSON.parse(fs.readFileSync(new URL('../../docs/research/agent-packages/opencode-web-compat.json', import.meta.url), 'utf8'));
assert.equal(crypto.createHash('sha256').update(source).digest('hex'), metadata.assetSha256);
const legacy = vm.createContext({ assert });
vm.runInContext('Map.groupBy = undefined; Promise.withResolvers = undefined;', legacy);
vm.runInContext(source, legacy);
await vm.runInContext(`(async () => {
  const object = {};
  const values = [object, object, NaN, NaN, -0, 0];
  const grouped = Map.groupBy(values, value => value);
  assert.equal(grouped.get(object).length, 2);
  assert.equal(grouped.get(NaN).length, 2);
  assert.equal(grouped.get(0).length, 2);
  assert.equal(grouped.size, 3);
  assert.equal(Map.groupBy([], value => value).size, 0);
  assert.equal(Object.keys(Map).includes('groupBy'), false);
  let closed = false;
  function* iterator() { try { yield 1; yield 2; } finally { closed = true; } }
  assert.throws(() => Map.groupBy(iterator(), () => { throw new Error('callback'); }), /callback/);
  assert.equal(closed, true);
  class Derived extends Promise {}
  const deferred = Derived.withResolvers();
  assert.equal(deferred.promise instanceof Derived, true);
  deferred.resolve(42);
  deferred.reject(new Error('already resolved'));
  assert.equal(await deferred.promise, 42);
  const rejected = Promise.withResolvers();
  rejected.reject(new Error('expected rejection'));
  await assert.rejects(rejected.promise, /expected rejection/);
  assert.equal(typeof AgentMHost, 'undefined');
})()`, legacy);
const native = vm.createContext({ assert });
vm.runInContext('globalThis.originalGroupBy = Map.groupBy; globalThis.originalResolvers = Promise.withResolvers;', native);
vm.runInContext(source, native);
vm.runInContext('assert.equal(Map.groupBy, originalGroupBy); assert.equal(Promise.withResolvers, originalResolvers);', native);
vm.runInContext(source, native);
vm.runInContext('assert.equal(Map.groupBy, originalGroupBy); assert.equal(Promise.withResolvers, originalResolvers);', native);
const originalAny = Object.getOwnPropertyDescriptor(AbortSignal, 'any');
try {
  Object.defineProperty(AbortSignal, 'any', { value: undefined, configurable: true, writable: true });
  const browser = vm.createContext({ assert, AbortController, AbortSignal, EventTarget });
  vm.runInContext(source, browser);
  await vm.runInContext(`(async () => {
    const first = new AbortController(), second = new AbortController();
    const composite = AbortSignal.any(new Set([first.signal, second.signal]));
    assert.equal(composite.aborted, false);
    assert.equal(AbortSignal.any([]).aborted, false);
    let count = 0;
    composite.addEventListener('abort', () => count++);
    const reason = { cancelled: 'directory browse' };
    second.abort(reason);
    assert.equal(composite.aborted, true);
    assert.equal(composite.reason, reason);
    assert.equal(first.signal.aborted, false);
    first.abort('later');
    assert.equal(count, 1);
    assert.equal(composite.reason, reason);
    assert.equal(AbortSignal.any([second.signal, first.signal]).reason, reason);
    assert.throws(() => AbortSignal.any([second.signal, {}]), { name: 'TypeError' });
    for (const input of [null, 1, {}, [new AbortController()]]) {
      assert.throws(() => AbortSignal.any(input), { name: 'TypeError' });
    }
    const controller = new AbortController();
    const nested = AbortSignal.any([AbortSignal.any([controller.signal, controller.signal])]);
    await Promise.resolve();
    controller.abort('runtime disconnected');
    assert.equal(nested.reason, 'runtime disconnected');
  })()`, browser);
} finally { Object.defineProperty(AbortSignal, 'any', originalAny); }
const browserNative = vm.createContext({ AbortController, AbortSignal, EventTarget });
vm.runInContext(source, browserNative);
assert.equal(AbortSignal.any, originalAny.value);
console.log('WebView compatibility passed: grouping, promise capabilities, abort composition/validation/reason, native API preservation');
