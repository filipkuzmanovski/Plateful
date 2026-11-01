import { SITE } from '@/content/site';
import { ContentPage, MailLink, Strong, Ul } from './ContentPage';

export default function PrivacyPage() {
  return (
    <ContentPage
      title="Privacy Policy"
      showUpdated
      lede="This page explains what personal data Plateful handles, why, and what you can do about it."
      sections={[
        {
          id: 'who-we-are',
          title: 'Who we are',
          body: (
            <p>
              {SITE.name} is run by {SITE.operator}, based in {SITE.city}, {SITE.country}. You can reach us at{' '}
              <MailLink />. {SITE.name} is a student portfolio project, so there is no company behind it, but we take
              your data seriously and handle it as described here.
            </p>
          ),
        },
        {
          id: 'what-we-collect',
          title: 'What we collect',
          body: (
            <Ul>
              <li>
                <Strong>Account data:</Strong> your email address and your password. The password is stored hashed by
                our authentication provider, so we never see it.
              </li>
              <li>
                <Strong>Profile:</Strong> your first and last name.
              </li>
              <li>
                <Strong>Age confirmation:</Strong> we record that you confirmed you are 13 or older, and when. Your
                date of birth is checked in your browser and never sent or stored.
              </li>
              <li>
                <Strong>Content you post:</Strong> recipes, photos and videos, ratings, comments, likes and dislikes.
              </li>
              <li>
                <Strong>Food preferences.</Strong> When you sign up we ask about food allergies, your diet, and
                ingredients you like or avoid. Allergy and diet details can say something about your health, so we use
                them only to filter and personalise recipe suggestions for you. They are never shown to other people.
                You can change them at any time on your profile, and they are deleted together with your account.
              </li>
              <li>
                <Strong>Security:</Strong> the two-factor authentication devices you set up, which are managed by our
                authentication provider.
              </li>
              <li>
                <Strong>Technical data</Strong> that our providers process to run the service, such as your IP address
                and request logs.
              </li>
            </Ul>
          ),
        },
        {
          id: 'what-we-do-not-collect',
          title: 'What we do not collect',
          body: (
            <p>
              There is no advertising on {SITE.name}. We do not use tracking or analytics cookies, and we do not sell
              your data.
            </p>
          ),
        },
        {
          id: 'how-we-use-it',
          title: 'How we use it',
          body: (
            <Ul>
              <li>To run your account and show your content.</li>
              <li>
                To keep the service secure: signing in, two-factor authentication, and an automatic sign-out after 60
                minutes of inactivity.
              </li>
              <li>To respond to you when you contact us.</li>
            </Ul>
          ),
        },
        {
          id: 'legal-bases',
          title: 'Legal bases (GDPR)',
          body: (
            <p>
              We rely on the contract between you and us (providing the service you signed up for), on our legitimate
              interests (keeping the service secure and preventing abuse), and on your consent where it applies.
            </p>
          ),
        },
        {
          id: 'browser-storage',
          title: 'Storage in your browser',
          body: (
            <p>
              {SITE.name} keeps two things in your browser: your sign-in session, which is needed to keep you signed
              in, and the time of your last activity, which is used for the inactivity sign-out. Both are strictly
              necessary for the service to work. They are not used for tracking.
            </p>
          ),
        },
        {
          id: 'who-processes-your-data',
          title: 'Who processes your data',
          body: (
            <>
              <Ul>
                <li>
                  <Strong>Supabase</Strong> provides our database, authentication and photo and video storage. Its
                  servers are in the EU, in Stockholm, Sweden.
                </li>
                <li>
                  <Strong>Our web hosting providers</Strong> serve the site to you. They will be named here once the
                  site is deployed.
                </li>
              </Ul>
              <p>Spoonacular supplies recipe data only. No user data is sent to it.</p>
            </>
          ),
        },
        {
          id: 'ai-recipes',
          title: 'AI recipes',
          body: (
            <>
              <p>
                When you ask {SITE.name} to make a recipe, or open today's meals, an AI service writes it. The recipe
                is made by Groq. If Groq is not available, it is made by Google Gemini.
              </p>
              <p>
                <Strong>What is sent:</Strong> the ingredients you typed, the meal type, a list of ingredients to
                avoid (built from your allergies, diet and dislikes), the cuisines and ingredients you like, and a few
                example recipes from a research dataset.
              </p>
              <p>
                <Strong>What is never sent:</Strong> your name, your email address or anything that identifies your
                account.
              </p>
              <p>
                The free tier of Google Gemini may use what is sent to improve Google's products, and human reviewers
                may read it. Groq's data retention is switched off. Please do not type personal information into the
                ingredient box.
              </p>
              <p>
                AI recipes are stored privately on your account and are deleted together with it. A recipe you have not
                saved is deleted after 30 days.
              </p>
            </>
          ),
        },
        {
          id: 'public-content',
          title: 'Public content',
          body: (
            <p>
              Recipes, photos and videos, ratings, comments and your display name are visible to anyone who visits{' '}
              {SITE.name}, not only to people who are signed in. Please do not post anything you want to keep private.
            </p>
          ),
        },
        {
          id: 'how-long-we-keep-it',
          title: 'How long we keep it',
          body: (
            <p>
              We keep your data for as long as your account exists. If you ask us to delete it, we delete your account
              data and your content within 30 days, except where the law requires us to keep something.
            </p>
          ),
        },
        {
          id: 'your-rights',
          title: 'Your rights',
          body: (
            <>
              <p>You have the right to:</p>
              <Ul>
                <li>access the data we hold about you;</li>
                <li>have it corrected or deleted;</li>
                <li>restrict or object to how we use it;</li>
                <li>receive it in a portable format;</li>
                <li>withdraw your consent at any time, where we rely on it.</li>
              </Ul>
              <p>
                To use any of these rights, email <MailLink />. You also have the right to complain to{' '}
                {SITE.dataProtectionAuthority}.
              </p>
            </>
          ),
        },
        {
          id: 'children',
          title: 'Children',
          body: (
            <p>
              {SITE.name} is not for children under 13. We do not knowingly collect their data. If you think a child has
              given us their data, contact us and we will delete it.
            </p>
          ),
        },
        {
          id: 'security',
          title: 'Security',
          body: (
            <p>
              We protect your data with encryption in transit (HTTPS), hashed passwords, optional two-factor
              authentication and the inactivity sign-out. No system is perfectly secure, and we cannot promise that
              nothing will ever go wrong.
            </p>
          ),
        },
        {
          id: 'changes',
          title: 'Changes to this policy',
          body: (
            <p>
              When we change this policy we update the date at the top of the page, and we announce significant changes
              on the site.
            </p>
          ),
        },
        {
          id: 'contact',
          title: 'Contact',
          body: (
            <p>
              Questions about this policy or your data? Email <MailLink />.
            </p>
          ),
        },
      ]}
    />
  );
}
