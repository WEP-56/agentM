import { GITHUB_URL } from '@/data/agents';
import { isNative, nativeRequest } from './native';

export interface AppUpdate {
  found: boolean;
  currentVersion: string;
  updateAvailable: boolean;
  latestVersion?: string;
  tag?: string;
  notes?: string;
}

export async function openProjectPage(page: 'repository' | 'releases' | 'release', tag = '') {
  if (isNative) { await nativeRequest('openProjectPage', { page, tag }); return; }
  const url = page === 'repository' ? GITHUB_URL : page === 'release' ? `${GITHUB_URL}/releases/tag/${encodeURIComponent(tag)}` : `${GITHUB_URL}/releases/latest`;
  window.open(url, '_blank', 'noopener,noreferrer');
}
