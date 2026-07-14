import { setupServer } from 'msw/node';

// Individual tests register handlers with server.use(...).
export const server = setupServer();
