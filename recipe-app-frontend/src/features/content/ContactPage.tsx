import { SITE } from '@/content/site';
import { ContentPage, MailLink, Ul } from './ContentPage';

export default function ContactPage() {
  return (
    <ContentPage title="Contact us" lede="We would like to hear from you, whether it is a question, a bug or a kind word.">
      <p>Email is the best way to reach us:</p>
      <p className="font-serif text-[22px] break-words text-ink sm:text-[28px]">
        <MailLink />
      </p>
      <p>
        <a
          href={`mailto:${SITE.contactEmail}`}
          className="inline-flex min-h-11 items-center justify-center rounded-full bg-ink px-5 py-2.5 text-[15px] font-semibold whitespace-nowrap text-on-ink transition-colors hover:bg-ink/90"
        >
          Email us
        </a>
      </p>
      <h2 className="pt-6 font-serif text-[28px] leading-tight font-medium tracking-[-0.015em] text-ink">What to write to us about</h2>
      <Ul>
        <li>Questions or feedback about {SITE.name}.</li>
        <li>Reporting a recipe or a comment.</li>
        <li>Privacy or data requests, such as getting a copy of your data or asking us to delete it.</li>
        <li>Problems signing in or with two-factor authentication.</li>
      </Ul>
      <p className="text-ink-soft">We usually reply within a few days.</p>
    </ContentPage>
  );
}
