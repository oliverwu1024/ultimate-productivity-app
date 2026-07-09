import { LegalChrome } from "../legal";

export const metadata = {
  title: "Terms & conditions — Ultiq",
  description: "The terms under which you use Ultiq.",
};

export default function TermsPage() {
  return <LegalChrome ns="terms" sectionCount={7} titleKey="meta.terms" />;
}
