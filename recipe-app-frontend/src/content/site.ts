/**
 * Site-wide facts used by the footer and the content pages (About, Privacy, Terms, Contact).
 * This is the only place these values live: change them here and every page follows.
 */
export const SITE = {
  name: 'Plateful',
  operator: 'The Plateful team',
  contactEmail: 'plateful.contact@example.com',
  country: 'North Macedonia',
  city: 'Skopje',
  dataProtectionAuthority: 'the Personal Data Protection Agency of North Macedonia (azlp.mk)',
  legalLastUpdated: '30 October 2026',
  copyrightYear: 2026,
} as const;
