import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// Unmount rendered components and wipe storage between tests so they cannot influence each other.
afterEach(() => {
  cleanup();
  sessionStorage.clear();
});
