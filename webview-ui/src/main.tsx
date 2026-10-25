import './index.css';

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import App from './App.tsx';
import { isBrowserRuntime, isDesktopRuntime } from './runtime';
import { deliverLocal } from './transport/index.js';

async function main() {
  // browserMock is for Vite dev mode only (UI prototyping without a server).
  // In standalone server mode, assets are loaded server-side and sent over WebSocket.
  if (isBrowserRuntime && import.meta.env.DEV) {
    const { initBrowserMock } = await import('./browserMock.js');
    await initBrowserMock();
  }
  // Desktop shell: the renderer loads its own bundled assets (the local runtime
  // sends no sprites). Loading runs alongside the first render; the transport
  // holds the frames until useExtensionMessages subscribes, and the office
  // leaves "Loading..." on the layout frame.
  if (isDesktopRuntime) {
    void import('./localAssets.js')
      .then(async ({ loadLocalAssets }) => {
        for (const message of await loadLocalAssets()) deliverLocal(message);
      })
      .catch((err: unknown) => console.error('[Webview] Failed to load bundled assets', err));
  }
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

main().catch(console.error);
