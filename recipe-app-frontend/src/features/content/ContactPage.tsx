import { SITE } from '@/content/site';
import { ContentPage, MailLink, Ul } from './ContentPage';

export default function ContactPage() {
  return (
    <ContentPage title="Contact us" lede="We would like to hear from you, whether it is a question, a bug or a kind word.">
      <p>Email is the best way to reach us:</p>
      <p className="font-serif text-xl break-words text-olive sm:text-3xl">
        <MailLink />
      </p>
      <p>
        <a
          href={`mailto:${SITE.contactEmail}`}
          className="inline-flex items-center justify-center rounded-full bg-olive px-5 py-2.5 text-sm font-semibold whitespace-nowrap text-cream shadow-button transition duration-200 ease-out hover:-translate-y-px hover:bg-olive/90 hover:shadow-lift"
        >
          Email us
        </a>
      </p>
      <h2 className="pt-6 font-serif text-2xl font-semibold tracking-tight text-olive">What to write to us about</h2>
      <Ul>
        <li>Questions or feedback about {SITE.name}.</li>
        <li>Reporting a recipe or a comment.</li>
        <li>Privacy or data requests, such as getting a copy of your data or asking us to delete it.</li>
        <li>Problems signing in or with two-factor authentication.</li>
      </Ul>
      <p className="text-olive/70">We usually reply within a few days.</p>
    </ContentPage>
  );
}
