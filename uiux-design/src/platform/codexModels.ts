// CC Switch 5ae6ad3888ba4543f6fad343c87656a97bd69da4, model_capabilities.rs.
// Keep the exact registry aligned with CodexDocuments.defaultImageInput.
const textOnly = new Set([
  'ark-code-latest', 'deepseek-chat', 'deepseek-reasoner', 'glm-5.1', 'glm-5.2', 'glm-5.3',
  'kat-coder', 'kat-coder-pro', 'kat-coder-pro v1', 'kat-coder-pro v2', 'kat-coder-pro-v1', 'kat-coder-pro-v2',
  'ling-2.5-1t', 'ling-2.6-1t', 'longcat-2.0', 'longcat-flash-chat', 'minimax-m2.7', 'minimax-m2.7-highspeed',
  'mimo-v2.5-pro', 'qwen3-coder-480b', 'qwen3-coder-480b-a35b-instruct', 'qwen3-coder-flash', 'qwen3-coder-next',
  'qwen3-coder-plus', 'step-3.5-flash', 'step-3.5-flash-2603', 'us.deepseek.r1-v1',
]);
export const codexImageDefault = (model: string) => !textOnly.has(model.trim().toLowerCase().replace(/\[1m\]$/, '').trim().split('/').pop() ?? '');
