"use client";

import { useI18n } from "@/i18n/context";
import { Button } from "@/components/ui/button";
import type { Locale } from "@/i18n/en";

const locales: Locale[] = ["en", "ru"];

export default function LanguageSwitcher() {
  const { locale, setLocale } = useI18n();

  return (
    <div className="flex items-center">
      {locales.map((loc) => (
        <Button
          key={loc}
          variant={locale === loc ? "default" : "ghost"}
          size="sm"
          onClick={() => setLocale(loc)}
          className="h-8 rounded-none px-3 text-xs first:rounded-l-md last:rounded-r-md"
        >
          {loc.toUpperCase()}
        </Button>
      ))}
    </div>
  );
}
