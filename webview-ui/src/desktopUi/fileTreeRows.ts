import type { FileEntry } from '../../../core/src/messages.js';

export interface TreeRow {
  path: string;
  name: string;
  depth: number;
  directory: boolean;
}

/** One collator for every comparison: `localeCompare` with options builds one per call. */
const NAME_ORDER = new Intl.Collator(undefined, { sensitivity: 'base' });

function compareEntries(a: TreeRow, b: TreeRow): number {
  if (a.directory !== b.directory) return a.directory ? -1 : 1;
  return NAME_ORDER.compare(a.name, b.name);
}

/**
 * The runtime's flat entry list (`relativePath` with `/`, depth <= 2) as rows
 * in tree order: each folder's children right under it, folders first, then
 * names without case (architecture.md 최소 파일 트리).
 */
export function treeRows(files: readonly FileEntry[]): TreeRow[] {
  const children = new Map<string, TreeRow[]>();
  for (const entry of files) {
    const parts = entry.relativePath.split('/').filter((part) => part !== '');
    if (parts.length === 0) continue;
    const parent = parts.slice(0, -1).join('/');
    const row: TreeRow = {
      path: parts.join('/'),
      name: parts[parts.length - 1],
      depth: parts.length - 1,
      directory: entry.directory,
    };
    const siblings = children.get(parent) ?? [];
    siblings.push(row);
    children.set(parent, siblings);
  }
  const rows: TreeRow[] = [];
  const visit = (parent: string) => {
    for (const row of [...(children.get(parent) ?? [])].sort(compareEntries)) {
      rows.push(row);
      if (row.directory) visit(row.path);
    }
  };
  visit('');
  return rows;
}
