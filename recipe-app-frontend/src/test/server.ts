import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';

// Individual tests register handlers with server.use(...). The one default: the home page's
// "Today's meals" strip asks for the meals of any signed-in user, and most tests don't care.
export const server = setupServer(
  http.get('http://localhost:8080/api/users/me/daily-meals', () => HttpResponse.json({ status: 'unavailable' })),
);
