/** Athena's mark: a plain "A" on a light tile. Decorative, so hidden from screen readers. */
export default function BrandMark({ size = 32 }) {
  return (
    <svg
      className="brand-mark"
      width={size}
      height={size}
      viewBox="0 0 64 64"
      aria-hidden="true"
      focusable="false"
    >
      <rect width="64" height="64" rx="14" fill="#f4f1ea" />
      <path
        d="M19 47 32 16l13 31M24 37h16"
        fill="none"
        stroke="#1f2328"
        strokeWidth="5.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}
