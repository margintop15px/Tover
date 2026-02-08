import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Tover - Marketplace Turnover Tracker",
  description: "Track your marketplace turnover, stock health, and critical inventory",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body className="antialiased">
        {children}
      </body>
    </html>
  );
}
