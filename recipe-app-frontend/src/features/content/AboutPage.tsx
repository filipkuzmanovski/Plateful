import { Link } from 'react-router';
import { SITE } from '@/content/site';
import { ContentPage, contentLinkClass, Ul } from './ContentPage';

export default function AboutPage() {
  return (
    <ContentPage
      title="About us"
      lede={`${SITE.name} is a place to share, discover, rate and discuss recipes from kitchens around the world.`}
      sections={[
        {
          id: 'why',
          title: 'Why it exists',
          body: (
            <p>
              Good food travels. A dish from a harbour town in Greece, a street stall in Jamaica or a family table
              just down the road deserves a home where anyone can find it, cook it and talk about it. {SITE.name} is that home: home
              cooks post what they love, and everyone else can try it and say how it went.
            </p>
          ),
        },
        {
          id: 'what-you-can-do',
          title: 'What you can do',
          body: (
            <Ul>
              <li>
                Browse by region, or by sea: the Mediterranean, the Caribbean, the North Sea, the Sea of Japan and
                Pacific, and the Gulf of Mexico.
              </li>
              <li>Filter by diet, cooking time and rating, search by title or ingredient, and sort the results.</li>
              <li>Post your own recipes with photos and a video.</li>
              <li>
                Cook with what you have: list your ingredients or scan your fridge, and get a new recipe made for
                you.
              </li>
              <li>Turn a recipe post into your own version, adjusted to your food preferences.</li>
              <li>See three meals made for you each day.</li>
              <li>Rate recipes, leave comments and like the ones you enjoy.</li>
              <li>Protect your account with two-factor authentication.</li>
            </Ul>
          ),
        },
        {
          id: 'who',
          title: 'Who is behind it',
          body: (
            <p>
              {SITE.name} is a student portfolio project. It was built at the Faculty of Computer Science and
              Engineering (FINKI), Ss. Cyril and Methodius University in {SITE.city}, and is run by {SITE.operator}. It
              is a real, working site, but it is a learning project and not a company.
            </p>
          ),
        },
        {
          id: 'where-recipes-come-from',
          title: 'Where the recipes come from',
          body: (
            <p>
              Most recipes are written by home cooks on {SITE.name}. We also imported a selection from Spoonacular, and
              each one is credited to its original source.
            </p>
          ),
        },
        {
          id: 'say-hello',
          title: 'Say hello',
          body: (
            <p>
              Questions, ideas or something that looks wrong? Please{' '}
              <Link to="/contact" className={contentLinkClass}>
                contact us
              </Link>
              .
            </p>
          ),
        },
      ]}
    />
  );
}
