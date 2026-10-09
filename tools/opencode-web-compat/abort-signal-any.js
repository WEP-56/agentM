// DOM AbortSignal.any for WebViews that have AbortController but predate signal composition.
// Preserve the native signal/reason objects; never alter a source controller.
if (typeof AbortSignal === 'function' && typeof AbortSignal.any !== 'function') {
  const aborted = Object.getOwnPropertyDescriptor(AbortSignal.prototype, 'aborted').get;
  const reason = Object.getOwnPropertyDescriptor(AbortSignal.prototype, 'reason').get;
  const add = EventTarget.prototype.addEventListener;
  const remove = EventTarget.prototype.removeEventListener;
  const controllers = new WeakMap();
  const registry = typeof FinalizationRegistry === 'function'
    ? new FinalizationRegistry(entries => clear(entries)) : null;

  function clear(entries) {
    for (const [signal, listener] of entries) remove.call(signal, 'abort', listener);
    entries.length = 0;
  }
  function listen(input, state) {
    const onAbort = () => {
      const output = state.reference.deref();
      const controller = output && controllers.get(output);
      clear(state.entries);
      registry?.unregister(state.token);
      if (controller) controller.abort(reason.call(input));
    };
    state.entries.push([input, onAbort]);
    add.call(input, 'abort', onAbort, { once: true });
  }
  const implementation = {
    any(signals) {
      if (signals == null || typeof signals[Symbol.iterator] !== 'function') {
        throw new TypeError('AbortSignal.any requires an iterable of AbortSignal objects');
      }
      const inputs = Array.from(signals);
      // Validate every member before returning even when an earlier signal is already aborted.
      for (const signal of inputs) aborted.call(signal);
      const controller = new AbortController();
      const output = controller.signal;
      for (const signal of inputs) {
        if (aborted.call(signal)) {
          controller.abort(reason.call(signal));
          return output;
        }
      }
      // The WeakMap keeps the controller alive while consumers retain its signal, without
      // keeping discarded compositions alive through listeners on a long-lived runtime signal.
      controllers.set(output, controller);
      const state = {
        reference: typeof WeakRef === 'function' ? new WeakRef(output) : { deref: () => output },
        entries: [],
        token: {},
      };
      for (const signal of new Set(inputs)) listen(signal, state);
      if (state.entries.length) registry?.register(output, state.entries, state.token);
      return output;
    },
  };
  Object.defineProperty(AbortSignal, 'any', { value: implementation.any, writable: true, configurable: true, enumerable: true });
}
