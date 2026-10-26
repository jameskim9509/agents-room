import { memo, useMemo } from 'react';

import type { FileEntry } from '../../../core/src/messages.js';
import { FILE_TREE_INDENT_PX } from '../constants.js';
import { treeRows } from './fileTreeRows.js';
import { T } from './strings.js';

/**
 * The minimal read-only file tree of the open project (SCRUM-31). Memoised on
 * the entry list: the project keeps its object across agent events, so a
 * streaming run never sorts or re-renders up to 500 rows again (SCRUM-22).
 */
export const FileTree = memo(function FileTree({
  files,
  truncated,
}: {
  files: FileEntry[];
  truncated: boolean;
}) {
  const rows = useMemo(() => treeRows(files), [files]);
  return (
    <div className="flex flex-col min-h-0" data-testid="file-tree">
      {rows.length === 0 && <div className="text-2xs text-text-muted">{T.filesEmpty}</div>}
      <ul className="m-0 p-0 list-none overflow-auto pixel-scrollbar min-h-0">
        {rows.map((row) => (
          <li
            key={row.path}
            className={`text-2xs leading-tight whitespace-nowrap overflow-hidden text-ellipsis ${
              row.directory ? 'text-text' : 'text-text-muted'
            }`}
            style={{ paddingLeft: row.depth * FILE_TREE_INDENT_PX }}
            title={row.path}
            data-path={row.path}
          >
            {row.directory ? `${row.name}/` : row.name}
          </li>
        ))}
      </ul>
      {truncated && (
        <div className="text-2xs text-warning mt-4" data-testid="file-tree-truncated">
          {T.filesTruncated}
        </div>
      )}
    </div>
  );
});
