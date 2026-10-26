/**
 * Generate bindings from core/asyncapi.yaml using @asyncapi/modelina.
 *
 *   tsx scripts/generate-messages.ts
 *     -> core/src/messages.ts (committed; CI checks it for drift)
 *   tsx scripts/generate-messages.ts --java --out <dir> [--package <name>]
 *     -> <dir>/<package path>/*.java (not committed; the runtime build calls this)
 *
 * TypeScript pipeline:
 *  1. Parse yaml via Modelina's TypeScriptGenerator (which uses asyncapi parser internally).
 *  2. Apply two custom constraints:
 *     - propertyKey: bypass Modelina's hardcoded reserved-keyword renaming (Modelina
 *       renames `type` -> `reservedType` and `status` -> `reservedStatus`, which would
 *       break the discriminated-union narrowing pattern used everywhere in the webview).
 *     - constant: always render `const: 'foo'` as the inline literal `'foo'` instead
 *       of an enum-style reference (`AnonymousSchema_N.FOO`) -- the latter is invalid
 *       TS when `enumType: 'union'` produces type aliases.
 *  3. Filter out unreferenced AnonymousSchema_* enum aliases that Modelina splits but
 *     our `constant` override leaves dead.
 *  4. Concatenate all model outputs into a single core/src/messages.ts file with a
 *     header banner (DO NOT EDIT MANUALLY). Modelina inlines nested unions, so the
 *     ADR 0005 `AgentEvent` union is declared by name and every property that
 *     holds an AgentEvent refers to it. `RuntimeServerMessage` and
 *     `RuntimeClientMessage` follow their unions: the members without `x-legacy`.
 *  5. Run prettier --write + eslint --fix on the file so it matches repo style and
 *     is reviewable as code (committed file, not gitignored).
 *
 * Java pipeline (agents-room runtime, ADR 0005 decision 11):
 *  1. Process the yaml into Modelina's meta model and drop every union member marked
 *     `x-legacy: true` (the inherited pixel-agents frames). The Java runtime serves
 *     only agents-room frames; legacy-only shapes become unreachable and are not emitted.
 *  2. JavaFileGenerator with the Jackson preset (`com.fasterxml.jackson.annotation.*`,
 *     which Jackson 3 still uses) and equals/hashCode. No toString: messages carry
 *     prompts and paths, and toString output ends up in logs.
 *  3. Stable names: each discriminator `const` would become its own
 *     `AnonymousSchema_N` enum, numbered by traversal order. The typeMapping and
 *     `constant` overrides render those fields as `String` with the literal value,
 *     and the dead enums are not written. Any other anonymous schema fails the run:
 *     name it in the yaml instead.
 *  4. The AgentEvent interface declares a getter and a setter for every envelope
 *     field (`x-envelope` in the yaml), with the type the event classes use, so the
 *     runtime's event normalizer can stamp `seq` and `runId` and track `lastSeq`
 *     through the interface. Modelina alone declares only `getKind()`. A member
 *     whose envelope field is missing or typed differently stops the run.
 *  5. Write one file per model under <out>/<package path>/. Files there that this
 *     generator wrote before are replaced; any other file stops the run.
 */

import {
  AsyncAPIInputProcessor,
  ConstrainedEnumModel,
  type ConstrainedMetaModel,
  ConstrainedObjectModel,
  ConstrainedReferenceModel,
  ConstrainedUnionModel,
  FormatHelpers,
  JAVA_COMMON_PRESET,
  JAVA_JACKSON_PRESET,
  JavaFileGenerator,
  type OutputModel,
  TypeScriptGenerator,
  UnionModel,
} from '@asyncapi/modelina';
import { execSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';

const REPO_ROOT = path.resolve(__dirname, '..');
const YAML_PATH = path.join(REPO_ROOT, 'core', 'asyncapi.yaml');
const OUTPUT_PATH = path.join(REPO_ROOT, 'core', 'src', 'messages.ts');

const HEADER = `/**
 * AUTO-GENERATED FROM core/asyncapi.yaml. DO NOT EDIT MANUALLY.
 *
 * Run \`npm run asyncapi:generate\` to regenerate.
 *
 * Source of truth: the yaml at core/asyncapi.yaml.
 * Editors and clients in any language can consume the spec directly.
 */

`;

const AGENT_EVENT = 'AgentEvent';
const AGENT_EVENT_HOST = 'AgentEventMessage';
const AGENT_EVENT_PROPERTY = 'event';
/** Each top-level union and the name of its agents-room half (TS only, see declareRuntimeUnions). */
const RUNTIME_UNIONS = [
  ['ServerMessage', 'RuntimeServerMessage'],
  ['ClientMessage', 'RuntimeClientMessage'],
] as const;
const AGENT_EVENT_ENVELOPE = 'x-envelope';

const JAVA_BANNER_FIRST_LINE = '// AUTO-GENERATED FROM core/asyncapi.yaml. DO NOT EDIT MANUALLY.';
const JAVA_BANNER = `${JAVA_BANNER_FIRST_LINE}
// Run \`npm run asyncapi:generate:java -- --out <dir>\` to regenerate.
`;
const DEFAULT_JAVA_PACKAGE = 'agentsroom.contract';
const JAVA_PACKAGE_PATTERN = /^[a-z_][a-z0-9_]*(\.[a-z_][a-z0-9_]*)*$/;
const ANONYMOUS_PREFIX = 'AnonymousSchema_';

interface Options {
  target: 'ts' | 'java';
  out?: string;
  packageName: string;
}

async function main(): Promise<void> {
  const options = parseArgs(process.argv.slice(2));
  if (!fs.existsSync(YAML_PATH)) {
    console.error(`[generate-messages] ${YAML_PATH} not found.`);
    process.exit(1);
  }
  const yaml = fs.readFileSync(YAML_PATH, 'utf-8');
  if (options.target === 'java') {
    await generateJava(yaml, options);
  } else {
    await generateTypeScript(yaml);
  }
}

function parseArgs(args: string[]): Options {
  const options: Options = { target: 'ts', packageName: DEFAULT_JAVA_PACKAGE };
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === '--java') {
      options.target = 'java';
    } else if (arg === '--out' || arg === '--package') {
      const value = args[++i];
      if (!value || value.startsWith('--')) fail(`${arg} needs a value.`);
      if (arg === '--out') options.out = value;
      else options.packageName = value;
    } else {
      fail(`unknown argument '${arg}'. Usage: [--java --out <dir> [--package <name>]]`);
    }
  }
  if (options.target === 'ts' && (options.out !== undefined || args.includes('--package'))) {
    fail('--out and --package apply only to --java.');
  }
  if (options.target === 'java') {
    if (options.out === undefined) fail('--java needs --out <dir>.');
    if (!JAVA_PACKAGE_PATTERN.test(options.packageName)) {
      fail(`'${options.packageName}' is not a valid Java package name.`);
    }
  }
  return options;
}

async function generateTypeScript(yaml: string): Promise<void> {
  const generator = new TypeScriptGenerator({
    modelType: 'interface',
    enumType: 'union',
    mapType: 'record',
    moduleSystem: 'ESM',
    useJavascriptReservedKeywords: false,
    constraints: {
      // Bypass reserved-keyword renaming so `type` and `status` stay verbatim.
      // Without this, discriminated-union narrowing (`msg.type === 'foo'`) breaks.
      propertyKey: ({ objectPropertyModel }) => objectPropertyModel.propertyName,
      // Always render const: '<value>' as the inline literal '<value>'. Default
      // Modelina behavior produces enum-style references that aren't valid TS
      // when enumType: 'union' generates plain type aliases.
      constant: ({ constrainedMetaModel }) => {
        const constOptions = constrainedMetaModel.options?.const;
        return constOptions ? `'${String(constOptions.originalInput)}'` : undefined;
      },
    },
  });

  const models = await generator.generate(yaml);

  // Drop unreferenced single-value anonymous enum aliases. Modelina splits each
  // `const` schema into its own ConstrainedEnumModel; our `constant` override
  // inlines the literal so the alias is dead code. Multi-value enums (real
  // string-literal unions) stay -- they ARE referenced by their containing
  // schema. The yaml should still extract them to named schemas for readability;
  // this filter only drops the const-derived garbage.
  const filtered = models.filter((m) => {
    const isAnon = m.modelName.startsWith(ANONYMOUS_PREFIX);
    const isEnum = m.model.constructor.name === 'ConstrainedEnumModel';
    if (!isAnon || !isEnum) return true;
    // Keep multi-value enums (still referenced); drop single-value (inlined).
    const enumModel = m.model as { values?: unknown[] };
    return (enumModel.values?.length ?? 0) > 1;
  });

  // Inline objects that Modelina extracts to AnonymousSchema_N interfaces are
  // still referenced. Renaming them in-place to a stable name is out of scope --
  // the yaml is authored to extract reusable shapes (AgentSeatMeta, etc.) so
  // the AnonymousSchema_N shouldn't appear in practice. Warn loudly if any do.
  const leakingAnon = filtered.filter((m) => m.modelName.startsWith(ANONYMOUS_PREFIX));
  if (leakingAnon.length > 0) {
    console.warn(
      `[generate-messages] WARNING: ${leakingAnon.length} anonymous schema(s) leaked into output: ` +
        leakingAnon.map((m) => m.modelName).join(', ') +
        '. Consider extracting them to named schemas in core/asyncapi.yaml.',
    );
  }

  // Concatenate. Each model.result is a complete TS declaration (interface/type).
  // We need to re-export the unions and the FurnitureAssetMessage interface as
  // top-level named exports. Modelina prepends `interface Foo` (no `export`) by
  // default when emitted via .generate(); we add `export` to every declaration.
  const declarations = filtered.map((m) => exportify(m.result));
  nameAgentEventUnion(filtered, declarations);
  declareRuntimeUnions(filtered, declarations);
  const body = declarations.join('\n\n');

  fs.writeFileSync(OUTPUT_PATH, HEADER + body + '\n', 'utf-8');
  console.log(`[generate-messages] wrote ${OUTPUT_PATH} (${filtered.length} models)`);

  // Format + lint --fix the generated file so the committed file passes CI.
  try {
    execSync(`npx prettier --write ${quote(OUTPUT_PATH)}`, {
      cwd: REPO_ROOT,
      stdio: 'inherit',
    });
  } catch (err) {
    console.error('[generate-messages] prettier failed:', err);
    process.exit(1);
  }
  try {
    execSync(`npx eslint --fix ${quote(path.relative(REPO_ROOT, OUTPUT_PATH))}`, {
      cwd: REPO_ROOT,
      stdio: 'inherit',
    });
  } catch {
    // ESLint may exit non-zero if there are unfixable warnings; that's okay --
    // we want to see them but not fail generation.
    console.warn('[generate-messages] eslint --fix reported issues (non-fatal).');
  }
}

/**
 * Modelina's TS generator splits out only objects and enums, so the ADR 0005
 * AgentEvent union would stay inline, with no name, everywhere it is used
 * (`AgentEventMessage.event`, `AgentSnapshot.recentEvents`). Declare it right
 * after its host and point every use at it, reusing the union text Modelina
 * rendered for the host property.
 */
function nameAgentEventUnion(models: OutputModel[], declarations: string[]): void {
  if (models.some((m) => m.modelName === AGENT_EVENT)) return; // Modelina named it itself.
  const hostIndex = models.findIndex((m) => m.modelName === AGENT_EVENT_HOST);
  const host = models[hostIndex];
  const property =
    host?.model instanceof ConstrainedObjectModel
      ? host.model.properties[AGENT_EVENT_PROPERTY]
      : undefined;
  if (!(property?.property instanceof ConstrainedUnionModel)) {
    fail(
      `${AGENT_EVENT_HOST}.${AGENT_EVENT_PROPERTY} is not a union; cannot declare ${AGENT_EVENT}.`,
    );
  }
  const unionText = property.property.type;
  if (declarations[hostIndex].split(unionText).length !== 2) {
    fail(`could not find the ${AGENT_EVENT} union exactly once in ${AGENT_EVENT_HOST}.`);
  }
  for (let i = 0; i < declarations.length; i++) {
    declarations[i] = declarations[i].split(unionText).join(AGENT_EVENT);
  }
  declarations.splice(
    hostIndex + 1,
    0,
    `/** One ADR 0005 AgentEvent, discriminated by \`kind\`. */\nexport type ${AGENT_EVENT} = ${unionText};`,
  );
}

/**
 * The agents-room half of each top-level union: its members without
 * `x-legacy`, declared right after the union (`RuntimeServerMessage` after
 * `ServerMessage`). The desktop renderer types its runtime socket with them,
 * so a frame the runtime never sends cannot be handled there and an upstream
 * command cannot be sent to the runtime. The Java bindings need no such
 * union: they leave the legacy members out altogether.
 */
function declareRuntimeUnions(models: OutputModel[], declarations: string[]): void {
  for (const [unionName, runtimeName] of RUNTIME_UNIONS) {
    const model = models.find((m) => m.modelName === unionName)?.model;
    if (!(model instanceof ConstrainedUnionModel)) {
      fail(`${unionName} is not a union; cannot declare ${runtimeName}.`);
    }
    const members = model.union.filter((member) => !isLegacy(member.originalInput));
    if (members.length === 0) fail(`union ${unionName} has no agents-room members.`);
    const index = declarations.findIndex((d) => d.startsWith(`export type ${unionName} =`));
    if (index === -1) fail(`could not find the ${unionName} declaration.`);
    declarations.splice(
      index + 1,
      0,
      `/** The agents-room members of \`${unionName}\` (no \`x-legacy\`). */\n` +
        `export type ${runtimeName} = ${members.map((member) => member.type).join(' | ')};`,
    );
  }
}

async function generateJava(yaml: string, options: Options): Promise<void> {
  const outDir = path.resolve(options.out as string);
  const packageName = options.packageName;

  const inputModel = await new AsyncAPIInputProcessor().process(yaml);
  let dropped = 0;
  for (const model of Object.values(inputModel.models)) {
    if (!(model instanceof UnionModel)) continue;
    const kept = model.union.filter((member) => !isLegacy(member.originalInput));
    dropped += model.union.length - kept.length;
    if (kept.length === 0) fail(`union ${model.name} has no agents-room members.`);
    model.union = kept;
  }

  let envelopeDeclared = false;
  const generator = new JavaFileGenerator({
    collectionType: 'List',
    presets: [
      JAVA_JACKSON_PRESET,
      {
        preset: JAVA_COMMON_PRESET,
        options: { equal: true, hashCode: true, classToString: false },
      },
      {
        union: {
          additionalContent: ({ model, content }) => {
            if (model.name !== AGENT_EVENT) return content;
            envelopeDeclared = true;
            return [content, ...envelopeAccessors(model)].filter(Boolean).join('\n');
          },
        },
      },
    ],
    typeMapping: {
      // A reference to a const-derived enum becomes a plain String field.
      Reference: ({ constrainedModel }) =>
        isConstEnum(constrainedModel.ref) ? 'String' : constrainedModel.name,
    },
    constraints: {
      // ...whose initializer is the literal: `private final String type = "snapshot";`
      constant: ({ constrainedMetaModel }) => {
        const constOptions = constrainedMetaModel.options?.const;
        return constOptions ? JSON.stringify(String(constOptions.originalInput)) : undefined;
      },
    },
  });
  const models = await generator.generateCompleteModels(inputModel, { packageName });
  if (!envelopeDeclared) fail(`no ${AGENT_EVENT} union was rendered; its envelope is undeclared.`);

  const unstable = models.filter(
    (m) => m.modelName.startsWith(ANONYMOUS_PREFIX) && !isConstEnum(m.model),
  );
  if (unstable.length > 0) {
    fail(
      `anonymous schema(s) would get unstable Java names: ${unstable.map((m) => m.modelName).join(', ')}. ` +
        'Extract them to named schemas in core/asyncapi.yaml.',
    );
  }

  const packageDir = path.join(outDir, ...packageName.split('.'));
  clearGeneratedJava(packageDir);
  fs.mkdirSync(packageDir, { recursive: true });

  const importOfAnonymous = new RegExp(
    `^import ${escapeRegExp(packageName)}\\.${ANONYMOUS_PREFIX}\\d+;\\r?\\n`,
    'gm',
  );
  const written = models
    .filter((m) => !m.modelName.startsWith(ANONYMOUS_PREFIX))
    .sort((a, b) => a.modelName.localeCompare(b.modelName));
  for (const m of written) {
    const source = m.result.replace(importOfAnonymous, '');
    if (source.includes(ANONYMOUS_PREFIX)) {
      fail(`${m.modelName}.java still references an anonymous schema.`);
    }
    fs.writeFileSync(
      path.join(packageDir, `${m.modelName}.java`),
      `${JAVA_BANNER}${source}\n`,
      'utf-8',
    );
  }
  console.log(
    `[generate-messages] wrote ${written.length} Java files to ${packageDir} ` +
      `(package ${packageName}; ${dropped} legacy union members left out)`,
  );
}

/**
 * `T getX();` and `void setX(T x);` for every AgentEvent envelope field, in
 * `x-envelope` order, with the property name and Java type every member class
 * uses (Modelina renders the members' own accessors with the same names).
 */
function envelopeAccessors(union: ConstrainedUnionModel): string[] {
  const envelope = (union.originalInput as Record<string, unknown> | undefined)?.[
    AGENT_EVENT_ENVELOPE
  ];
  if (typeof envelope !== 'object' || envelope === null) {
    fail(`${AGENT_EVENT} has no '${AGENT_EVENT_ENVELOPE}' in core/asyncapi.yaml.`);
  }
  const lines: string[] = [];
  for (const field of Object.keys(envelope)) {
    let shared: { name: string; type: string } | undefined;
    for (const member of union.union) {
      const event =
        member instanceof ConstrainedReferenceModel && member.ref instanceof ConstrainedObjectModel
          ? member.ref
          : undefined;
      if (!event) fail(`${AGENT_EVENT} member ${member.name} is not an object.`);
      const property = Object.values(event.properties).find(
        (p) => p.unconstrainedPropertyName === field,
      );
      if (!property || property.property.options.const) {
        fail(`${event.name} lacks the settable envelope field '${field}'.`);
      }
      const found = { name: property.propertyName, type: property.property.type };
      if (shared && (shared.name !== found.name || shared.type !== found.type)) {
        fail(
          `envelope field '${field}' is ${shared.type} ${shared.name} in one ${AGENT_EVENT} ` +
            `member but ${found.type} ${found.name} in ${event.name}.`,
        );
      }
      shared = found;
    }
    if (!shared) fail(`${AGENT_EVENT} has no members.`);
    const accessor = FormatHelpers.toPascalCase(shared.name);
    lines.push(`${shared.type} get${accessor}();`);
    lines.push(`void set${accessor}(${shared.type} ${shared.name});`);
  }
  return lines;
}

function isLegacy(originalInput: unknown): boolean {
  return (
    typeof originalInput === 'object' &&
    originalInput !== null &&
    (originalInput as Record<string, unknown>)['x-legacy'] === true
  );
}

function isConstEnum(model: ConstrainedMetaModel | undefined): boolean {
  return (
    model instanceof ConstrainedEnumModel &&
    model.name.startsWith(ANONYMOUS_PREFIX) &&
    model.values.length === 1
  );
}

/**
 * Remove files a previous run wrote; refuse to touch anything else. Every entry
 * is checked before the first delete, so a refusal leaves the directory as it was.
 */
function clearGeneratedJava(packageDir: string): void {
  if (!fs.existsSync(packageDir)) return;
  const files = fs.readdirSync(packageDir, { withFileTypes: true }).map((entry) => {
    const file = path.join(packageDir, entry.name);
    const generated =
      entry.isFile() &&
      entry.name.endsWith('.java') &&
      fs.readFileSync(file, 'utf-8').startsWith(JAVA_BANNER_FIRST_LINE);
    if (!generated) fail(`refusing to overwrite ${file}: it was not written by this generator.`);
    return file;
  });
  for (const file of files) fs.unlinkSync(file);
}

/**
 * Modelina emits declarations like `interface Foo { ... }` or
 * `type Foo = A | B;` without `export`. Add it.
 */
function exportify(decl: string): string {
  const trimmed = decl.trimStart();
  if (trimmed.startsWith('export ')) return decl;
  return `export ${trimmed}`;
}

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function quote(s: string): string {
  // execSync runs through cmd.exe on Windows, which does NOT strip single
  // quotes -- prettier would receive a filename containing literal quote chars
  // ("No files matching the pattern"). Use double quotes there; POSIX shells
  // get single-quote escaping.
  if (process.platform === 'win32') {
    return `"${s.replace(/"/g, '\\"')}"`;
  }
  return `'${s.replace(/'/g, "'\\''")}'`;
}

function fail(message: string): never {
  console.error(`[generate-messages] ${message}`);
  process.exit(1);
}

main().catch((err) => {
  console.error('[generate-messages] failed:', err);
  process.exit(1);
});
