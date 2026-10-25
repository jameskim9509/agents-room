import typescriptEslint from 'typescript-eslint';
import eslintConfigPrettier from 'eslint-config-prettier';
import simpleImportSort from 'eslint-plugin-simple-import-sort';
import pixelAgentsPlugin from './eslint-rules/pixel-agents-rules.mjs';

export default [
  {
    // Gradle output of the Java runtime (test reports ship their own JS) and
    // the bundled output of desktop/esbuild.mjs.
    ignores: ['runtime/build/**', 'runtime/.gradle/**', 'desktop/dist/**'],
  },
  {
    files: ['**/*.ts'],
  },
  {
    plugins: {
      '@typescript-eslint': typescriptEslint.plugin,
      'simple-import-sort': simpleImportSort,
      'pixel-agents': pixelAgentsPlugin,
    },

    languageOptions: {
      parser: typescriptEslint.parser,
      ecmaVersion: 2022,
      sourceType: 'module',
    },

    rules: {
      '@typescript-eslint/naming-convention': [
        'error',
        {
          selector: 'import',
          format: ['camelCase', 'PascalCase'],
        },
      ],

      curly: 'error',
      eqeqeq: 'error',
      'no-throw-literal': 'error',
      'simple-import-sort/imports': 'error',
      'simple-import-sort/exports': 'error',
      'pixel-agents/no-inline-colors': 'error',
    },
  },
  {
    files: ['adapters/vscode/constants.ts', 'desktop/src/constants.ts'],
    rules: {
      'pixel-agents/no-inline-colors': 'off',
    },
  },
  eslintConfigPrettier,
];
