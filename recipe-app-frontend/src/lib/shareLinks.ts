export interface ShareLinks {
  url: string;
  text: string;
  title: string;
  facebook: string;
  x: string;
  whatsapp: string;
  pinterest: string;
  email: string;
}

/** The share data and the per-network URLs for a recipe. Every dynamic value is percent-encoded. */
export function buildShareLinks({
  origin,
  id,
  title,
  image,
}: {
  origin: string;
  id: string;
  title: string;
  image: string | null;
}): ShareLinks {
  const url = `${origin}/recipes/${encodeURIComponent(id)}`;
  const text = `${title} on Plateful`;
  const u = encodeURIComponent(url);
  const t = encodeURIComponent(text);
  const media = image ? `&media=${encodeURIComponent(image)}` : '';
  return {
    url,
    text,
    title,
    facebook: `https://www.facebook.com/sharer/sharer.php?u=${u}`,
    x: `https://twitter.com/intent/tweet?url=${u}&text=${t}`,
    whatsapp: `https://wa.me/?text=${t}%20${u}`,
    pinterest: `https://pinterest.com/pin/create/button/?url=${u}${media}&description=${t}`,
    email: `mailto:?subject=${encodeURIComponent(title)}&body=${t}%0A${u}`,
  };
}
