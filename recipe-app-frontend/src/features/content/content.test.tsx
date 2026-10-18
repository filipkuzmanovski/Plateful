import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { recipeFixture, pageOf } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import { SITE } from '@/content/site';

const BASE = 'http://localhost:8080';

beforeEach(() => {
  server.use(
    http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([recipeFixture()]))),
    http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json([])),
    http.get(`${BASE}/api/recipes/seas`, () => HttpResponse.json([])),
    http.get(`${BASE}/api/recipes/r-1`, () => HttpResponse.json(recipeFixture())),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf([]))),
  );
});

const pages = [
  { path: '/about', link: 'About us', h1: 'About us', title: 'About us · Plateful' },
  { path: '/privacy', link: 'Privacy Policy', h1: 'Privacy Policy', title: 'Privacy Policy · Plateful' },
  { path: '/terms', link: 'Terms of Service', h1: 'Terms of Service', title: 'Terms of Service · Plateful' },
  { path: '/contact', link: 'Contact', h1: 'Contact us', title: 'Contact us · Plateful' },
] as const;

describe('footer', () => {
  test('renders on the browse page with the Explore and Legal navs', async () => {
    renderApp('/');
    const footer = await screen.findByRole('contentinfo');
    expect(within(footer).getByText(/recipes from every coast, shared by home cooks/i)).toBeInTheDocument();
    const explore = within(footer).getByRole('navigation', { name: 'Explore' });
    expect(within(explore).getByRole('link', { name: 'Browse' })).toHaveAttribute('href', '/');
    expect(within(explore).getByRole('link', { name: 'About us' })).toHaveAttribute('href', '/about');
    expect(within(explore).getByRole('link', { name: 'Contact' })).toHaveAttribute('href', '/contact');
    const legal = within(footer).getByRole('navigation', { name: 'Legal' });
    expect(within(legal).getByRole('link', { name: 'Privacy Policy' })).toHaveAttribute('href', '/privacy');
    expect(within(legal).getByRole('link', { name: 'Terms of Service' })).toHaveAttribute('href', '/terms');
    expectNoEmoji(footer);
  });

  test('renders on a recipe page too', async () => {
    renderApp('/recipes/r-1');
    expect(await screen.findByRole('heading', { name: /bruschetta pork & pasta/i })).toBeInTheDocument();
    const footer = screen.getByRole('contentinfo');
    expect(within(footer).getByRole('navigation', { name: 'Explore' })).toBeInTheDocument();
    expect(within(footer).getByRole('navigation', { name: 'Legal' })).toBeInTheDocument();
  });

  test('bottom row states the copyright, the student project and Spoonacular', async () => {
    renderApp('/about');
    const footer = await screen.findByRole('contentinfo');
    expect(footer).toHaveTextContent(
      `© ${SITE.copyrightYear} ${SITE.name}. A student portfolio project. Some recipe data provided by Spoonacular.`,
    );
  });

  test.each(pages)('the footer link "$link" opens the $h1 page', async ({ link, h1 }) => {
    const user = userEvent.setup();
    renderApp('/');
    const footer = await screen.findByRole('contentinfo');
    await user.click(within(footer).getByRole('link', { name: link }));
    expect(await screen.findByRole('heading', { level: 1, name: h1 })).toBeInTheDocument();
  });
});

describe.each(pages)('$path page', ({ path, h1, title }) => {
  test('shows its h1, sets the document title, and has no emoji', async () => {
    renderApp(path);
    expect(await screen.findByRole('heading', { level: 1, name: h1 })).toBeInTheDocument();
    expect(document.title).toBe(title);
    expectNoEmoji();
  });

  test('scrolls to the top when it opens', async () => {
    const scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    renderApp(path);
    await screen.findByRole('heading', { level: 1, name: h1 });
    expect(scrollTo).toHaveBeenCalled();
    scrollTo.mockRestore();
  });
});

describe('hash links', () => {
  test('a malformed hash renders the page and scrolls to the top', async () => {
    const scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    renderApp('/privacy#%');
    expect(await screen.findByRole('heading', { level: 1, name: 'Privacy Policy' })).toBeInTheDocument();
    expect(scrollTo).toHaveBeenCalledWith(0, 0);
    scrollTo.mockRestore();
  });

  test('an unknown hash scrolls to the top', async () => {
    const scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    renderApp('/privacy#nope');
    await screen.findByRole('heading', { level: 1, name: 'Privacy Policy' });
    expect(scrollTo).toHaveBeenCalledWith(0, 0);
    scrollTo.mockRestore();
  });

  test('a valid hash scrolls that section into view instead of the top', async () => {
    const intoView = vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(() => {});
    const scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    renderApp('/privacy#your-rights');
    await screen.findByRole('heading', { level: 1, name: 'Privacy Policy' });
    const section = document.getElementById('your-rights');
    expect(section).not.toBeNull();
    expect(intoView.mock.contexts).toContain(section);
    expect(scrollTo).not.toHaveBeenCalled();
    intoView.mockRestore();
    scrollTo.mockRestore();
  });
});

describe('About us', () => {
  test('says it is a student portfolio project and links to Contact', async () => {
    renderApp('/about');
    await screen.findByRole('heading', { level: 1, name: 'About us' });
    const main = screen.getByRole('main');
    expect(main).toHaveTextContent(/student portfolio project/i);
    expect(main).toHaveTextContent(SITE.operator);
    expect(main).toHaveTextContent(/spoonacular/i);
    expect(within(main).getByRole('link', { name: /contact/i })).toHaveAttribute('href', '/contact');
  });
});

describe('Privacy Policy', () => {
  test('shows the last-updated date from SITE', async () => {
    renderApp('/privacy');
    expect(await screen.findByText(`Last updated: ${SITE.legalLastUpdated}`)).toBeInTheDocument();
  });

  test('states that the date of birth is not stored', async () => {
    renderApp('/privacy');
    await screen.findByRole('heading', { level: 1, name: 'Privacy Policy' });
    expect(screen.getByRole('main')).toHaveTextContent(
      /date of birth is checked in your browser and never sent or stored/i,
    );
  });

  test('names the operator, location and contact from SITE, and is modest about the project', async () => {
    renderApp('/privacy');
    await screen.findByRole('heading', { level: 1, name: 'Privacy Policy' });
    const main = screen.getByRole('main');
    expect(main).toHaveTextContent(SITE.operator);
    expect(main).toHaveTextContent(`${SITE.city}, ${SITE.country}`);
    expect(main).toHaveTextContent(SITE.dataProtectionAuthority);
    expect(main).toHaveTextContent(/student portfolio project/i);
    const mails = within(main).getAllByRole('link', { name: SITE.contactEmail });
    for (const a of mails) expect(a).toHaveAttribute('href', `mailto:${SITE.contactEmail}`);
  });

  test('mentions videos in what we collect, who sees it and where it is stored', async () => {
    renderApp('/privacy');
    await screen.findByRole('heading', { level: 1, name: 'Privacy Policy' });
    const main = screen.getByRole('main');
    expect(main).toHaveTextContent('recipes, photos and videos, ratings, comments, likes and dislikes');
    expect(main).toHaveTextContent('Recipes, photos and videos, ratings, comments and your display name are visible');
    expect(main).toHaveTextContent('authentication and photo and video storage');
  });

  test('has section headings as h2', async () => {
    renderApp('/privacy');
    await screen.findByRole('heading', { level: 1, name: 'Privacy Policy' });
    for (const name of ['Who we are', 'What we collect', 'What we do not collect', 'Your rights', 'Children'])
      expect(screen.getByRole('heading', { level: 2, name })).toBeInTheDocument();
  });
});

describe('Terms of Service', () => {
  test('shows the last-updated date from SITE', async () => {
    renderApp('/terms');
    expect(await screen.findByText(`Last updated: ${SITE.legalLastUpdated}`)).toBeInTheDocument();
  });

  test('covers videos in the sentence about copied content', async () => {
    renderApp('/terms');
    await screen.findByRole('heading', { level: 1, name: 'Terms of Service' });
    expect(screen.getByRole('main')).toHaveTextContent('recipes, photos or videos copied from someone else');
  });

  test('has the food-safety disclaimer heading', async () => {
    renderApp('/terms');
    expect(await screen.findByRole('heading', { level: 2, name: /food safety/i })).toBeInTheDocument();
  });

  test('says no warranty, as a student portfolio project', async () => {
    renderApp('/terms');
    await screen.findByRole('heading', { level: 1, name: 'Terms of Service' });
    expect(screen.getByRole('heading', { level: 2, name: 'No warranty' })).toBeInTheDocument();
    expect(screen.getByRole('main')).toHaveTextContent(/student portfolio project/i);
    expect(screen.getByRole('main')).toHaveTextContent(`courts in ${SITE.city}`);
  });
});

describe('Contact', () => {
  test('shows the email and an Email us button that points at the SITE address', async () => {
    renderApp('/contact');
    await screen.findByRole('heading', { level: 1, name: 'Contact us' });
    const main = screen.getByRole('main');
    expect(within(main).getByRole('link', { name: 'Email us' })).toHaveAttribute('href', `mailto:${SITE.contactEmail}`);
    expect(main).toHaveTextContent(SITE.contactEmail);
    expect(main).toHaveTextContent(/we usually reply within a few days/i);
    expect(within(main).queryByRole('textbox')).not.toBeInTheDocument();
  });
});
