import { LegalChrome } from "../legal";

export const metadata = {
  title: "Delete your account — Ultiq",
  description: "How to delete your Ultiq account and the data tied to it.",
};

export default function DeleteAccountPage() {
  return (
    <LegalChrome
      ns="del"
      sectionCount={6}
      titleKey="meta.delete"
      footerLinks={[
        { href: "/privacy", key: "footer.privacy" },
        { href: "/terms", key: "footer.terms" },
      ]}
    />
  );
}
