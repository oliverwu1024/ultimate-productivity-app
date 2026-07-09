import { LegalChrome } from "../legal";

export const metadata = {
  title: "Privacy policy — Ultiq",
  description: "How Ultiq collects, stores, and handles your data.",
};

export default function PrivacyPage() {
  return (
    <LegalChrome
      ns="privacy"
      sectionCount={12}
      titleKey="meta.privacy"
      footerLinks={[{ href: "/terms", key: "footer.terms" }]}
    />
  );
}
