import { SITE } from '@/content/site';
import { ContentPage, MailLink, Ul } from './ContentPage';

export default function TermsPage() {
  return (
    <ContentPage
      title="Terms of Service"
      showUpdated
      lede="These are the rules for using Plateful. They are short, and we have tried to keep them plain."
      sections={[
        {
          id: 'agreement',
          title: 'Agreement',
          body: <p>By using {SITE.name} you accept these terms. If you do not accept them, please do not use the site.</p>,
        },
        {
          id: 'eligibility',
          title: 'Eligibility',
          body: <p>You must be at least 13 years old to use {SITE.name}.</p>,
        },
        {
          id: 'your-account',
          title: 'Your account',
          body: (
            <p>
              Keep your password safe. You are responsible for what happens on your account. We recommend turning on
              two-factor authentication.
            </p>
          ),
        },
        {
          id: 'your-content',
          title: 'Your content',
          body: (
            <>
              <p>
                You keep ownership of what you post. You give {SITE.name} a non-exclusive, royalty-free licence to host,
                display and share it on the service, so that other people can see it.
              </p>
              <p>
                You confirm that you have the right to post it. Please do not post recipes, photos or videos copied from
                someone else without their permission. You can delete your content at any time.
              </p>
            </>
          ),
        },
        {
          id: 'acceptable-use',
          title: 'Acceptable use',
          body: (
            <>
              <p>Please do not use {SITE.name} for:</p>
              <Ul>
                <li>spam;</li>
                <li>harassment, or hateful or illegal content;</li>
                <li>pretending to be someone else;</li>
                <li>abusing the service, for example scraping it, overloading it or bypassing its security;</li>
                <li>spreading malware.</li>
              </Ul>
            </>
          ),
        },
        {
          id: 'moderation',
          title: 'Moderation',
          body: <p>We may remove content or suspend accounts that break these terms.</p>,
        },
        {
          id: 'imported-recipes',
          title: 'Imported recipes',
          body: (
            <p>
              Some recipes come from Spoonacular and are credited to their original sources. The rights to them remain
              with their owners.
            </p>
          ),
        },
        {
          id: 'food-safety',
          title: 'Food safety disclaimer',
          body: (
            <div className="rounded-2xl border border-terracotta-deep/25 bg-terracotta/10 p-5 text-olive">
              <p className="font-semibold">Check before you cook.</p>
              <p className="mt-2">
                Recipes, nutrition information, diet labels (such as vegan or gluten-free) and cooking times come from
                users and third parties, and they may be wrong. Always check the ingredients against your allergies and
                dietary needs, and follow safe food handling.
              </p>
            </div>
          ),
        },
        {
          id: 'no-warranty',
          title: 'No warranty',
          body: (
            <p>
              {SITE.name} is provided &ldquo;as is&rdquo;. It is a student portfolio project, and we do not guarantee
              that it will always be available or free of errors.
            </p>
          ),
        },
        {
          id: 'limitation-of-liability',
          title: 'Limitation of liability',
          body: (
            <p>
              To the extent the law allows, {SITE.operator} is not liable for any loss or damage that comes from using
              {' '}
              {SITE.name}.
            </p>
          ),
        },
        {
          id: 'ending',
          title: 'Ending your use',
          body: (
            <p>
              You can stop using {SITE.name} and ask us to delete your account at any time. We may suspend or close
              accounts that break these terms.
            </p>
          ),
        },
        {
          id: 'governing-law',
          title: 'Governing law',
          body: (
            <p>
              These terms are governed by the laws of {SITE.country}, and the courts in {SITE.city} will decide any
              dispute, unless consumer law where you live gives you other rights.
            </p>
          ),
        },
        {
          id: 'changes',
          title: 'Changes to these terms',
          body: (
            <p>
              We may update these terms. When we do, we change the date at the top of the page and announce significant
              changes on the site. If you keep using {SITE.name} after that, you accept the new terms.
            </p>
          ),
        },
        {
          id: 'contact',
          title: 'Contact',
          body: (
            <p>
              Questions about these terms? Email <MailLink />.
            </p>
          ),
        },
      ]}
    />
  );
}
