/**
 * Validate the contract fixtures in core/fixtures/ against core/asyncapi.yaml.
 *
 * The schemas come from the parsed AsyncAPI document (the parser that
 * `npm run asyncapi:validate` uses), so fixtures are checked against exactly what
 * the TS and Java bindings are generated from.
 *
 * - valid/server/*.json and valid/client/*.json hold one ServerMessage or
 *   ClientMessage each. Every one must validate.
 * - invalid/server/*.json and invalid/client/*.json hold
 *   { description, expect: { keyword, instancePath?, params? }, message }.
 *   The message must fail, and one error must match `expect` (params is a subset
 *   match), so a fixture cannot pass by failing for some other reason.
 * - Coverage: every agents-room frame, command and AgentEvent kind (union members
 *   without `x-legacy`) needs a valid fixture, and valid fixtures may not use
 *   legacy frames.
 * - Discriminators: every member of ServerMessage, ClientMessage and AgentEvent
 *   has its own required `type` / `kind` const, so new frames cannot collide with
 *   inherited ones.
 * - Layout: core/fixtures holds exactly those four folders, and they hold only
 *   `*.json` files. Anything else there (a misspelled or nested folder, a `.JSON`
 *   or `.txt` file, a link) fails the run instead of being skipped unchecked.
 *
 * Each fixture runs through two validators. The schema as written (oneOf)
 * decides pass/fail. A copy whose AsyncAPI `discriminator: <name>` is rewritten to
 * ajv's `{ propertyName: <name> }` reports errors from the selected variant only,
 * which is what `expect` is matched against. The two must agree.
 *
 * Both run ajv in strict mode, so an unknown keyword in the yaml (a misspelled
 * `minLenght`) fails schema compilation instead of silently dropping its rule.
 * Only the keywords in ANNOTATION_KEYWORDS (and the AsyncAPI string
 * `discriminator`) are allowed beyond JSON Schema.
 *
 * Exits 1 on any failure.
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { Parser } from '@asyncapi/parser';
import Ajv from 'ajv';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SCRIPT_DIR, '..');
const SPEC_PATH = path.join(REPO_ROOT, 'core', 'asyncapi.yaml');
const FIXTURES_DIR = path.join(REPO_ROOT, 'core', 'fixtures');

const UNIONS = { server: 'ServerMessage', client: 'ClientMessage' };
const EVENT_UNION = 'AgentEvent';
const EVENT_FRAME_TYPE = 'agentEvent';
const FIXTURE_KINDS = ['valid', 'invalid'];
const FIXTURE_NAME = /^[^.].*\.json$/;

/**
 * Non-JSON-Schema keywords the parsed schemas carry. ajv treats them as
 * annotations; strict mode rejects every other unknown keyword.
 */
const ANNOTATION_KEYWORDS = [
  // Inherited pixel-agents union member (no fixture, not in the Java bindings).
  'x-legacy',
  // The AgentEvent envelope, merged into every variant with YAML anchors.
  'x-envelope',
  'x-envelope-required',
  // Added to every schema by @asyncapi/parser.
  'x-parser-schema-id',
];

async function main() {
  const { document, diagnostics } = await new Parser().parse(fs.readFileSync(SPEC_PATH, 'utf8'));
  if (!document) {
    for (const d of diagnostics) console.error(`  ${d.message}`);
    console.error('[contract-check] core/asyncapi.yaml did not parse.');
    process.exit(1);
  }
  const schemas = document.components().schemas();

  const failures = [];
  const expected = new Set();
  const covered = new Set();
  let validCount = 0;
  let invalidCount = 0;

  const eventKinds = discriminatorValues(EVENT_UNION, schemas.get(EVENT_UNION).json(), failures);
  for (const kind of eventKinds) expected.add(`event:${kind}`);
  for (const unionName of Object.values(UNIONS)) {
    discriminatorValues(unionName, schemas.get(unionName).json(), failures);
  }
  // ajv cannot compile a discriminator with a missing or repeated tag; stop here.
  if (failures.length > 0) report(failures);

  const fixtures = collectFixtures(failures);
  const fixtureFiles = (kind, side) => fixtures.get(`${kind}/${side}`) ?? [];

  for (const [side, unionName] of Object.entries(UNIONS)) {
    const union = schemas.get(unionName).json();
    const legacyTypes = new Set();
    for (const variant of union.oneOf) {
      const type = variant.properties.type.const;
      if (variant['x-legacy'] === true) legacyTypes.add(type);
      else expected.add(`${side}:${type}`);
    }
    const asWritten = compile(unionName, createAjv(false), union);
    const byVariant = compile(unionName, createAjv(true), withAjvDiscriminators(union));

    for (const file of fixtureFiles('valid', side)) {
      validCount++;
      const message = readJson(file, failures);
      if (message === undefined) continue;
      const ok = asWritten(message);
      if (byVariant(message) !== ok) failures.push(`${rel(file)}: the two validators disagree`);
      if (!ok) failures.push(`${rel(file)}: rejected\n${formatErrors(byVariant.errors)}`);
      if (legacyTypes.has(message.type)) {
        failures.push(
          `${rel(file)}: '${message.type}' is a legacy frame; fixtures cover agents-room frames only`,
        );
      }
      covered.add(`${side}:${message.type}`);
      if (side === 'server' && message.type === EVENT_FRAME_TYPE)
        covered.add(`event:${message.event?.kind}`);
    }

    const invalidFiles = fixtureFiles('invalid', side);
    if (invalidFiles.length === 0) failures.push(`core/fixtures/invalid/${side}/ has no fixtures`);
    for (const file of invalidFiles) {
      invalidCount++;
      const fixture = readJson(file, failures);
      if (fixture === undefined) continue;
      const { description, expect, message } = fixture;
      if (
        typeof description !== 'string' ||
        typeof expect?.keyword !== 'string' ||
        typeof message !== 'object'
      ) {
        failures.push(`${rel(file)}: needs { description, expect: { keyword, ... }, message }`);
        continue;
      }
      const ok = asWritten(message);
      if (byVariant(message) !== ok) failures.push(`${rel(file)}: the two validators disagree`);
      if (ok) {
        failures.push(`${rel(file)}: accepted, but it must be rejected (${description})`);
        continue;
      }
      const instancePath = expect.instancePath ?? '';
      const matched = byVariant.errors.some(
        (e) =>
          e.keyword === expect.keyword &&
          e.instancePath === instancePath &&
          isSubset(expect.params, e.params),
      );
      if (!matched) {
        failures.push(
          `${rel(file)}: rejected, but not with ${expect.keyword} at '${instancePath}'\n${formatErrors(byVariant.errors)}`,
        );
      }
    }
  }

  for (const key of [...expected].sort()) {
    if (!covered.has(key)) failures.push(`no valid fixture covers ${key}`);
  }

  if (failures.length > 0) report(failures);
  const count = (prefix) => [...expected].filter((key) => key.startsWith(prefix)).length;
  console.log(
    `[contract-check] ${validCount} valid fixtures accepted, ${invalidCount} invalid fixtures rejected as expected. ` +
      `Covered ${count('server:')} frames, ${count('client:')} commands, ${count('event:')} AgentEvent kinds.`,
  );
}

function report(failures) {
  for (const failure of failures) console.error(`  FAIL ${failure}`);
  console.error(`[contract-check] ${failures.length} failure(s).`);
  process.exit(1);
}

/**
 * The discriminator `const` of every union member. A member without one, or two
 * members sharing one, is a failure: the bindings would route both to one class.
 */
function discriminatorValues(unionName, union, failures) {
  const property = union.discriminator;
  const seen = new Set();
  for (const variant of union.oneOf) {
    const value = variant.properties?.[property]?.const;
    if (typeof value !== 'string') {
      failures.push(`${unionName}: a member has no '${property}' const`);
    } else if (seen.has(value)) {
      failures.push(`${unionName}: '${property}: ${value}' is used by two members`);
    } else if (!variant.required?.includes(property)) {
      failures.push(`${unionName}: '${value}' does not list '${property}' as required`);
    }
    seen.add(value);
  }
  return seen;
}

function createAjv(discriminator) {
  const ajv = new Ajv({
    strict: true,
    // strictRequired rejects `then: { required: [project] }` (Snapshot): it does
    // not see `project` in the parent's `properties` from inside the branch. A
    // required name that matches no property is still caught by any valid fixture
    // holding that shape: with additionalProperties: false no message can pass.
    strictRequired: false,
    // Nullable fields are written `type: [string, 'null']`.
    allowUnionTypes: true,
    allErrors: true,
    discriminator,
  });
  ajv.addVocabulary(ANNOTATION_KEYWORDS);
  // The as-written copy keeps AsyncAPI's string form, which ajv does not know;
  // the oneOf alone decides there.
  if (!discriminator) ajv.addKeyword({ keyword: 'discriminator', schemaType: 'string' });
  // `format: int64` marks epoch milliseconds and sequence numbers.
  ajv.addFormat('int64', { type: 'number', validate: Number.isSafeInteger });
  return ajv;
}

/** A strict-mode compile error (unknown keyword, type conflict) stops the run. */
function compile(unionName, ajv, schema) {
  try {
    return ajv.compile(schema);
  } catch (error) {
    report([`${unionName}: the schema does not compile in ajv strict mode: ${error.message}`]);
  }
}

/**
 * Copy of `schema` with every AsyncAPI string discriminator in ajv's object form.
 * ajv's discriminator applies to objects only, and strict mode wants that said:
 * the copy adds `type: object` to a union that does not state it (every member
 * is an object, so no message changes outcome).
 */
function withAjvDiscriminators(schema) {
  const copy = structuredClone(schema);
  const seen = new Set();
  const visit = (node) => {
    if (typeof node !== 'object' || node === null || seen.has(node)) return;
    seen.add(node);
    if (typeof node.discriminator === 'string' && Array.isArray(node.oneOf)) {
      node.discriminator = { propertyName: node.discriminator };
      node.type ??= 'object';
    }
    for (const value of Object.values(node)) visit(value);
  };
  visit(copy);
  return copy;
}

/**
 * The fixture files under core/fixtures, keyed `<kind>/<side>`. Every entry that
 * is not one of the four fixture folders or a `*.json` file in one of them is a
 * failure, so nothing there goes unchecked.
 */
function collectFixtures(failures) {
  const fixtures = new Map();
  const unexpected = (dir, entry, allowed) => {
    const what = entry.isDirectory() ? 'folder' : entry.isFile() ? 'file' : 'entry';
    failures.push(
      `${rel(path.join(dir, entry.name))}: unexpected ${what}; ${rel(dir)}/ may hold only ${allowed}`,
    );
  };
  for (const kindEntry of listDir(FIXTURES_DIR)) {
    if (!kindEntry.isDirectory() || !FIXTURE_KINDS.includes(kindEntry.name)) {
      unexpected(FIXTURES_DIR, kindEntry, FIXTURE_KINDS.map((k) => `${k}/`).join(' and '));
      continue;
    }
    const kindDir = path.join(FIXTURES_DIR, kindEntry.name);
    for (const sideEntry of listDir(kindDir)) {
      if (!sideEntry.isDirectory() || !Object.hasOwn(UNIONS, sideEntry.name)) {
        unexpected(
          kindDir,
          sideEntry,
          Object.keys(UNIONS)
            .map((s) => `${s}/`)
            .join(' and '),
        );
        continue;
      }
      const sideDir = path.join(kindDir, sideEntry.name);
      const files = [];
      for (const fileEntry of listDir(sideDir)) {
        if (fileEntry.isFile() && FIXTURE_NAME.test(fileEntry.name)) {
          files.push(path.join(sideDir, fileEntry.name));
        } else {
          unexpected(sideDir, fileEntry, '*.json files');
        }
      }
      fixtures.set(`${kindEntry.name}/${sideEntry.name}`, files);
    }
  }
  return fixtures;
}

/** Directory entries in name order; links are neither files nor folders here. */
function listDir(dir) {
  return fs
    .readdirSync(dir, { withFileTypes: true })
    .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
}

function readJson(file, failures) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    failures.push(`${rel(file)}: not JSON (${error.message})`);
    return undefined;
  }
}

function isSubset(expected, actual) {
  if (expected === undefined) return true;
  return Object.entries(expected).every(([key, value]) => actual?.[key] === value);
}

function formatErrors(errors) {
  return (errors ?? [])
    .map(
      (e) =>
        `      ${e.instancePath || '/'} ${e.keyword}: ${e.message} ${JSON.stringify(e.params)}`,
    )
    .join('\n');
}

function rel(file) {
  return path.relative(REPO_ROOT, file).split(path.sep).join('/');
}

main().catch((error) => {
  console.error('[contract-check]', error);
  process.exit(1);
});
