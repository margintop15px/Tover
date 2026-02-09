"use client";

import { useI18n } from "@/i18n/context";
import type { Locale } from "@/i18n/en";

const labels: Record<Locale, string> = {
  en: "EN",
  ru: "RU",
};

const locales: Locale[] = ["en", "ru"];

export default function LanguageSwitcher() {
  const { locale, setLocale } = useI18n();

  return (
    <div className="flex items-center gap-1 rounded-md border border-zinc-200 dark:border-zinc-700">
      {locales.map((loc) => (
        <button
          key={loc}
          onClick={() => setLocale(loc)}
          className={`px-2.5 py-1 text-xs font-medium transition-colors ${
            locale === loc
              ? "bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900"
              : "text-zinc-500 hover:text-zinc-700 dark:text-zinc-400 dark:hover:text-zinc-200"
          } ${loc === locales[0] ? "rounded-l-md" : ""} ${loc === locales[locales.length - 1] ? "rounded-r-md" : ""}`}
        >
          {labels[loc]}
        </button>
      ))}
    </div>
  );
}
